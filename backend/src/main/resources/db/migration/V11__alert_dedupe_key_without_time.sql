-- =============================================================================
-- Alert deduplication: drop the time component from the key.
--
-- V6 built the key as `rule:zone:hour-bucket`, and its own comment promised
-- "a zone that stays congested for an hour produces one alert that stays open,
-- not sixty". That held only when the condition began just after a wall-clock
-- hour. A zone congested from 08:55 to 09:55 fell in two buckets and raised two
-- alerts — precisely the fatigue the mechanism exists to prevent. The engine's
-- integration test caught it on one CI run that happened to start at 18:57;
-- every earlier run started at a harmless minute.
--
-- The key is now `rule:zone_id`. Nothing else is needed to distinguish episodes:
-- a persistent condition refreshes one open alert, a condition that stops has
-- its alert closed by the auto-resolve pass once the evidence ages past
-- telemetry.max-age, and a later episode finds nothing open and raises fresh.
-- uq_alerts_dedupe_open is partial on unresolved rows, so reusing the key after
-- resolution is what it was designed to permit.
--
-- This migration has to run before an upgraded engine starts, or the first
-- cycle would fail to match the old-format keys and raise a duplicate for every
-- zone with an open alert.
-- =============================================================================

-- 1. Resolve the duplicates the bug produced.
--
-- Where a rule has more than one open alert on the same zone, they describe one
-- condition split across an hour boundary. Keep the newest, since that is the
-- one the engine would have been refreshing, and close the rest.
--
-- Closed rather than deleted: an operator may have acknowledged one of these,
-- and destroying that record to tidy up a schema change would remove evidence
-- of a human decision.
WITH duplicates AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY rule_code, zone_id
               ORDER BY zone_metric_window_start DESC NULLS LAST, raised_at DESC, id DESC
           ) AS rank
    FROM alerts
    WHERE status <> 'RESOLVED'
      AND zone_id IS NOT NULL
)
UPDATE alerts a
SET status          = 'RESOLVED',
    resolved_at     = GREATEST(now(), a.raised_at),
    resolution_note = 'Superseded: duplicate of the same condition, raised by an '
                   || 'hour-boundary defect in alert deduplication (V11).',
    updated_at      = now()
FROM duplicates d
WHERE a.id = d.id
  AND d.rank > 1;

-- 2. Rewrite the survivors to the new key format.
--
-- On zone_id rather than zone code. Zone codes are unique only within a city
-- (uq_zones_city_code_active), so two cities sharing a code such as 'CBD' would
-- collapse to one key and the unique index would silently reject the second
-- city's alert. The old key had the same flaw; it was masked by demo zone codes
-- that happen to carry a city prefix.
--
-- Only open alerts. A resolved alert's key records how it was identified at the
-- time, and rewriting history to match a new scheme would be a lie about what
-- the system did.
UPDATE alerts
SET dedupe_key = rule_code || ':' || zone_id,
    updated_at = now()
WHERE zone_id IS NOT NULL
  AND status <> 'RESOLVED';

COMMENT ON COLUMN alerts.dedupe_key IS
    'Identity of a condition: rule_code:zone_id. No time component - the open/resolved lifecycle separates episodes, not the clock. Keyed on zone_id because zone codes are unique only within a city.';
