#!/usr/bin/env bash
# Measure API latency against PRD §44.
#
# The PRD says targets "should be measured rather than assumed", and gives no
# numbers. So this measures, states what it measured on, and reports percentiles
# rather than an average — an average hides the slow tail, which is the part a
# user actually notices.
#
#   bash docs/verification/measure-performance.sh
#
# Requires a running backend and a populated database. Figures from a laptop
# with everything on one machine are not a production benchmark; they are a
# baseline that would catch a regression of the kind that matters.
set -uo pipefail

API=${API:-http://localhost:8080}
RUNS=${RUNS:-30}

set -a && . "$(dirname "$0")/../../.env" && set +a

AT=$(curl -s -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$CITYPULSE_BOOTSTRAP_ADMIN_EMAIL\",\"password\":\"$CITYPULSE_BOOTSTRAP_ADMIN_PASSWORD\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)

if [ -z "${AT:-}" ]; then
  echo "Could not authenticate. Is the backend running and CITYPULSE_BOOTSTRAP_ADMIN_* set?"
  exit 1
fi
AUTH="Authorization: Bearer $AT"

ZID=$(curl -s "$API/api/v1/live/by-slug/bengaluru" -H "$AUTH" \
  | python3 -c "import sys,json; z=json.load(sys.stdin)['data']['zones']; print(z[0]['zoneId'] if z else '')" 2>/dev/null)

echo "Measuring $RUNS requests per endpoint against $API"
echo "Database: $(psql -h localhost -U citypulse -d citypulse -tAc 'SELECT count(*) FROM zone_metrics' 2>/dev/null || echo '?') curated windows"
echo

printf "%-46s %8s %8s %8s %8s\n" "endpoint" "p50" "p95" "max" "status"
printf -- "----------------------------------------------------------------------------------\n"

measure() {
  local label=$1 path=$2
  local times=() code=""
  for _ in $(seq 1 "$RUNS"); do
    local out
    out=$(curl -s -o /dev/null -w '%{time_total} %{http_code}' "$API$path" -H "$AUTH")
    times+=("${out%% *}")
    code="${out##* }"
  done

  python3 - "$label" "$code" "${times[@]}" <<'PY'
import sys
label, code, *raw = sys.argv[1:]
ms = sorted(float(t) * 1000 for t in raw)
def pct(p):
    return ms[min(len(ms) - 1, int(round(p * (len(ms) - 1))))]
print(f"{label:<46} {pct(0.50):7.0f}m {pct(0.95):7.0f}m {ms[-1]:7.0f}m {code:>8}")
PY
}

measure "live snapshot (20 zones)"        "/api/v1/live/by-slug/bengaluru"
measure "alerts, open"                     "/api/v1/alerts?openOnly=true&size=50"
measure "alert summary"                    "/api/v1/alerts/summary"
[ -n "$ZID" ] && measure "zone history (6h)" "/api/v1/live/zones/$ZID/history"
[ -n "$ZID" ] && measure "forecast, all horizons" "/api/v1/forecasts/zones/$ZID?metric=occupancy_ratio"
measure "forecast accuracy"                "/api/v1/forecasts/accuracy"
measure "anomalies (24h)"                  "/api/v1/anomalies?citySlug=bengaluru&size=50"
measure "correlations"                     "/api/v1/anomalies/correlations?citySlug=bengaluru"
measure "city memory recall"               "/api/v1/anomalies/memory?citySlug=bengaluru&rainBand=NONE&hourBand=EVENING_PEAK"
measure "insights summary"                 "/api/v1/anomalies/insights?citySlug=bengaluru"
measure "zones list"                       "/api/v1/cities/by-slug/bengaluru"

echo
echo "p95 is the figure that matters: it is roughly the slowest request a user"
echo "notices in a session, and an average would hide it."
