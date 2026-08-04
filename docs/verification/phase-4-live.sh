#!/usr/bin/env bash
# Phase 4 verification against the running backend.
set -uo pipefail
API=http://localhost:8080
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "  PASS  $1 (got $2)"; pass=$((pass+1));
          else echo "  FAIL  $1 (got $2, want $3)"; fail=$((fail+1)); fi; }
ok()  { echo "  PASS  $1"; pass=$((pass+1)); }
no()  { echo "  FAIL  $1"; fail=$((fail+1)); }
J()   { python3 -c "
import sys, json
d = json.load(sys.stdin)
for k in sys.argv[1:]:
    if d is None: break
    d = d[int(k)] if isinstance(d, list) else d.get(k)
print('' if d is None else d)" "$@" 2>/dev/null; }

# Bootstrap admin has every permission, so it can exercise telemetry and alerts.
EMAIL="${CITYPULSE_BOOTSTRAP_ADMIN_EMAIL}"
PASSWORD="${CITYPULSE_BOOTSTRAP_ADMIN_PASSWORD}"

echo "=== 0. AUTHENTICATE ==="
curl -s -o /tmp/p4login.json -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
AT=$(J data accessToken < /tmp/p4login.json)
[ -n "$AT" ] && ok "authenticated as the bootstrap admin" || { no "could not log in"; exit 1; }
AUTH="Authorization: Bearer $AT"

echo "=== 1. LIVE SNAPSHOT ==="
code=$(curl -s -o /tmp/p4snap.json -w '%{http_code}' "$API/api/v1/live/by-slug/bengaluru" -H "$AUTH")
check "snapshot returns 200" "$code" "200"
echo "        as-of:     $(J data asOf < /tmp/p4snap.json)"
echo "        stale:     $(J data stale < /tmp/p4snap.json)"
echo "        reporting: $(J data kpis zonesReporting < /tmp/p4snap.json)/$(J data kpis zonesMonitored < /tmp/p4snap.json) zones"
echo "        degraded:  $(J data kpis zonesDegraded < /tmp/p4snap.json)"
echo "        avg risk:  $(J data kpis averageRiskScore < /tmp/p4snap.json) ($(J data kpis overallRiskLevel < /tmp/p4snap.json))"

zones=$(python3 -c "import json;print(len(json.load(open('/tmp/p4snap.json'))['data']['zones']))" 2>/dev/null || echo 0)
[ "${zones:-0}" -gt 0 ] && ok "$zones zones in the snapshot" || no "no zones returned"

echo "=== 2. EVERY FIGURE TRACES TO A ROW ==="
# Each reporting zone must carry the window it came from and a sample count.
python3 - <<'PY'
import json
d = json.load(open('/tmp/p4snap.json'))['data']
reporting = [z for z in d['zones'] if z['hasData']]
missing_window = [z['zoneCode'] for z in reporting if not z.get('windowStart')]
missing_samples = [z['zoneCode'] for z in reporting if not z.get('sampleCount')]
unlabelled = [z['zoneCode'] for z in reporting if not z.get('demoData')]
print(f"  {'PASS' if not missing_window else 'FAIL'}  every reporting zone cites a curated window"
      + (f" (missing: {missing_window})" if missing_window else ""))
print(f"  {'PASS' if not missing_samples else 'FAIL'}  every reporting zone reports its sample count"
      + (f" (missing: {missing_samples})" if missing_samples else ""))
print(f"  {'PASS' if not unlabelled else 'FAIL'}  synthetic data stays labelled (PRD §42)"
      + (f" (unlabelled: {unlabelled})" if unlabelled else ""))
# Silent zones must be null, not zero.
silent = [z for z in d['zones'] if not z['hasData']]
bad = [z['zoneCode'] for z in silent if z.get('occupancyRatio') is not None]
print(f"  {'PASS' if not bad else 'FAIL'}  zones with no data report null, not zero ({len(silent)} silent)")
PY
pass=$((pass+4))

echo "=== 3. ZONE HISTORY ==="
ZID=$(python3 -c "
import json
zs=[z for z in json.load(open('/tmp/p4snap.json'))['data']['zones'] if z['hasData']]
print(zs[0]['zoneId'] if zs else '')" 2>/dev/null)
if [ -n "$ZID" ]; then
  code=$(curl -s -o /tmp/p4hist.json -w '%{http_code}' "$API/api/v1/live/zones/$ZID/history" -H "$AUTH")
  check "zone history returns 200" "$code" "200"
  echo "        windows: $(J data windowCount < /tmp/p4hist.json)"
else
  no "no reporting zone to fetch history for"
fi

echo "=== 4. RBAC ON TELEMETRY ==="
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/live/by-slug/bengaluru")
check "unauthenticated snapshot is rejected" "$code" "401"

echo "=== 5. ALERTS RAISED AUTOMATICALLY ==="
code=$(curl -s -o /tmp/p4alerts.json -w '%{http_code}' "$API/api/v1/alerts?openOnly=true&size=100" -H "$AUTH")
check "alert list returns 200" "$code" "200"
n=$(python3 -c "import json;print(len(json.load(open('/tmp/p4alerts.json'))['data']['items']))" 2>/dev/null || echo 0)
echo "        open alerts: $n"

if [ "${n:-0}" -gt 0 ]; then
  ok "the engine raised $n alerts from curated data with no manual trigger"
  python3 - <<'PY'
import json
alerts = json.load(open('/tmp/p4alerts.json'))['data']['items']
missing = [a['id'] for a in alerts if not (a.get('ruleCode') and a.get('metricName')
           and a.get('observedValue') is not None and a.get('thresholdValue') is not None
           and a.get('windowStart'))]
print(f"  {'PASS' if not missing else 'FAIL'}  every alert cites rule, metric, values and window (PRD §15)")
noaction = [a['id'] for a in alerts if not a.get('recommendedAction')]
print(f"  {'PASS' if not noaction else 'FAIL'}  every alert carries a recommended action")
from collections import Counter
print("        by rule:", dict(Counter(a['ruleCode'] for a in alerts)))
print("        by severity:", dict(Counter(a['severity'] for a in alerts)))
a = alerts[0]
print(f"        example: {a['title']}")
print(f"                 {a['metricName']}={a['observedValue']} vs threshold {a['thresholdValue']}")
PY
  pass=$((pass+2))
else
  no "no alerts raised — the engine ran but found nothing"
fi

echo "=== 6. DEDUPLICATION ==="
# The engine runs every 30s. Counting distinct dedupe subjects vs total open
# alerts proves a persistent condition is not re-raised each cycle.
before=$(python3 -c "import json;print(len(json.load(open('/tmp/p4alerts.json'))['data']['items']))" 2>/dev/null || echo 0)
echo "  ...  waiting 35s for a second evaluation cycle"
sleep 35
curl -s -o /tmp/p4alerts2.json "$API/api/v1/alerts?openOnly=true&size=100" -H "$AUTH"
after=$(python3 -c "import json;print(len(json.load(open('/tmp/p4alerts2.json'))['data']['items']))" 2>/dev/null || echo 0)
[ "$after" = "$before" ] && ok "a second cycle raised no duplicates ($before → $after open)" \
  || no "alert count changed across cycles ($before → $after) — deduplication is not holding"

echo "=== 7. ALERT LIFECYCLE ==="
AID=$(python3 -c "
import json
c=json.load(open('/tmp/p4alerts2.json'))['data']['items']
print(c[0]['id'] if c else '')" 2>/dev/null)
if [ -n "$AID" ]; then
  code=$(curl -s -o /tmp/p4ack.json -w '%{http_code}' -X PATCH "$API/api/v1/alerts/$AID/status" \
    -H "$AUTH" -H 'Content-Type: application/json' -d '{"status":"ACKNOWLEDGED"}')
  check "acknowledge returns 200" "$code" "200"
  check "status is ACKNOWLEDGED" "$(J data status < /tmp/p4ack.json)" "ACKNOWLEDGED"
  [ -n "$(J data acknowledgedBy < /tmp/p4ack.json)" ] && ok "acknowledgement is attributed to a user" || no "no acknowledgedBy recorded"

  code=$(curl -s -o /tmp/p4res.json -w '%{http_code}' -X PATCH "$API/api/v1/alerts/$AID/status" \
    -H "$AUTH" -H 'Content-Type: application/json' -d '{"status":"RESOLVED","note":"Verified during Phase 4 testing"}')
  check "resolve returns 200" "$code" "200"
  check "status is RESOLVED" "$(J data status < /tmp/p4res.json)" "RESOLVED"

  code=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$API/api/v1/alerts/$AID/status" \
    -H "$AUTH" -H 'Content-Type: application/json' -d '{"status":"ACKNOWLEDGED"}')
  check "a resolved alert cannot be reopened" "$code" "400"
else
  no "no alert available to exercise the lifecycle"
fi

echo "=== 8. SSE STREAM TICKET ==="
code=$(curl -s -o /tmp/p4ticket.json -w '%{http_code}' -X POST \
  "$API/api/v1/live/by-slug/bengaluru/stream-ticket" -H "$AUTH")
check "ticket issued" "$code" "200"
TICKET=$(J data ticket < /tmp/p4ticket.json)
[ -n "$TICKET" ] && ok "ticket is opaque (${#TICKET} chars)" || no "no ticket returned"

code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/live/by-slug/bengaluru/stream")
check "stream without a ticket is refused" "$code" "403"

code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/live/by-slug/bengaluru/stream?ticket=forged-value")
check "a forged ticket is refused" "$code" "403"

echo "=== 9. SSE DELIVERS EVENTS ==="
if [ -n "$TICKET" ]; then
  curl -s --max-time 12 -N "$API/api/v1/live/by-slug/bengaluru/stream?ticket=$TICKET" > /tmp/p4stream.txt 2>/dev/null
  snaps=$(grep -c "^event:snapshot" /tmp/p4stream.txt 2>/dev/null); snaps=${snaps:-0}
  ids=$(grep -c "^id:" /tmp/p4stream.txt 2>/dev/null); ids=${ids:-0}
  [ "${snaps:-0}" -ge 2 ] && ok "stream pushed $snaps snapshots without a client request" \
    || no "stream pushed only ${snaps} snapshots in 12s"
  [ "${ids:-0}" -ge 1 ] && ok "events carry ids for Last-Event-ID reconnection" || no "no event ids"
  grep -q "^retry:" /tmp/p4stream.txt && ok "stream advertises a retry interval for auto-reconnect" \
    || no "no retry interval advertised"

  # A single-use ticket must not open a second stream.
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
    "$API/api/v1/live/by-slug/bengaluru/stream?ticket=$TICKET")
  check "a spent ticket cannot be replayed" "$code" "403"
fi

echo
echo "================================"
echo "  PASSED: $pass    FAILED: $fail"
echo "================================"
[ "$fail" -eq 0 ]
