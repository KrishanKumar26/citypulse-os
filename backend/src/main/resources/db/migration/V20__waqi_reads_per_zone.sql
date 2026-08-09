-- Correct the WAQI source's shaping parameters after the ingester changed shape.
--
-- V19 described a feed that drew a box around every monitored city, asked WAQI
-- for the stations inside it, and then decided which zone each belonged to. The
-- ingester now asks per zone instead, through `/feed/geo:`, for a reason worth
-- recording where the row lives:
--
-- `/map/bounds/` is a separate grant on some tokens. A working token can be
-- told "Invalid key" there, and that rejection is indistinguishable from a bad
-- token — on the one call that decides whether the whole feed runs. `/feed/geo:`
-- is the endpoint every token reaches, it answers the question actually being
-- asked ("which station covers this zone"), and it gives a zone one station or
-- none, which is what the curated layer wants: two stations of differing
-- quality inside one radius would average into a number neither reported.
--
-- `bounds` therefore describes nothing. It is removed rather than left as a
-- value that reads like configuration and controls nothing — this column is the
-- record of how a feed is shaped, and a stale key in it is a lie about that.
--
-- maxStationKm stays and still means what it said: `/feed/geo:` returns the
-- nearest station at *any* distance, so the limit is checked against the
-- station's own coordinates before a reading is attributed to a zone.

UPDATE data_sources
   SET config = (config - 'bounds') || '{"endpoint": "/feed/geo:"}'::jsonb
 WHERE code = 'waqi-air-quality';
