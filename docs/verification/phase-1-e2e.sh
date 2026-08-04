#!/usr/bin/env bash
# Phase 1 exit-criteria verification against the running backend.
#
# The backend rate-limits /api/v1/auth/** to 10 requests per minute per IP, so
# auth calls are paced to stay inside that window rather than disabling the
# limiter — the run then exercises the same configuration production uses.
set -uo pipefail
API=http://localhost:8080
EMAIL="e2e+$$@citypulse.local"
PASS='E2e-Verify!2026x'
pass=0; fail=0
AUTH_BUDGET=8          # stay under the limit of 10 per window
auth_used=0

check() { # check <label> <actual> <expected>
  if [ "$2" = "$3" ]; then printf '  PASS  %s (got %s)\n' "$1" "$2"; pass=$((pass+1));
  else printf '  FAIL  %s (got %s, want %s)\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}
ok() { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
no() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

# jget <file> <key> [key...] -> prints nested value or empty
jget() {
  local f=$1; shift
  python3 -c '
import sys, json
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
for k in sys.argv[2:]:
    if not isinstance(d, (dict, list)):
        sys.exit(0)
    try:
        d = d[int(k)] if isinstance(d, list) else d[k]
    except Exception:
        sys.exit(0)
print(d if d is not None else "")
' "$f" "$@"
}

# Spend one unit of the auth rate-limit budget, waiting for a fresh window if needed.
spend_auth() {
  auth_used=$((auth_used+1))
  if [ "$auth_used" -gt "$AUTH_BUDGET" ]; then
    echo "  ...  auth budget spent, waiting 62s for a fresh rate-limit window"
    sleep 62
    auth_used=1
  fi
}

echo "Waiting 62s so the run starts on a clean rate-limit window..."
sleep 62

echo "=== 1. SIGNUP ==="
spend_auth
code=$(curl -s -o /tmp/su.json -w '%{http_code}' -X POST "$API/api/v1/auth/signup" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"fullName\":\"E2E Verifier\"}")
check "signup returns 201" "$code" "201"

echo "=== 2. LOGIN ==="
spend_auth
code=$(curl -s -o /tmp/li.json -w '%{http_code}' -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
check "login returns 200" "$code" "200"
AT=$(jget /tmp/li.json data accessToken)
RT=$(jget /tmp/li.json data refreshToken)
[ -n "$AT" ] && ok "access token issued (${#AT} chars)" || no "no access token in login response"
[ -n "$RT" ] && ok "refresh token issued (${#RT} chars)" || no "no refresh token in login response"

echo "=== 3. PROTECTED ENDPOINT WITH TOKEN ==="
spend_auth
code=$(curl -s -o /tmp/me.json -w '%{http_code}' "$API/api/v1/auth/me" -H "Authorization: Bearer $AT")
check "/auth/me returns 200" "$code" "200"
check "/auth/me returns the caller" "$(jget /tmp/me.json data email)" "$EMAIL"
echo "        roles: $(python3 -c 'import json;print(json.load(open("/tmp/me.json"))["data"].get("roles"))' 2>/dev/null)"

echo "=== 4. PROTECTED ENDPOINT WITHOUT TOKEN ==="
spend_auth
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/auth/me")
check "unauthenticated call returns 401" "$code" "401"

echo "=== 5. RBAC: A NEW USER HITS ADMIN-ONLY ENDPOINTS (not rate limited) ==="
code=$(curl -s -o /tmp/403.json -w '%{http_code}' "$API/api/v1/users" -H "Authorization: Bearer $AT")
check "GET /users without permission returns 403" "$code" "403"
echo "        code: $(jget /tmp/403.json error code)"
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/audit-logs" -H "Authorization: Bearer $AT")
check "GET /audit-logs without permission returns 403" "$code" "403"
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/roles" -H "Authorization: Bearer $AT")
check "GET /roles without permission returns 403" "$code" "403"

echo "=== 6. REFRESH ==="
spend_auth
code=$(curl -s -o /tmp/rf.json -w '%{http_code}' -X POST "$API/api/v1/auth/refresh" \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}")
check "refresh returns 200" "$code" "200"
AT2=$(jget /tmp/rf.json data accessToken)
RT2=$(jget /tmp/rf.json data refreshToken)
{ [ -n "$AT2" ] && [ "$AT2" != "$AT" ]; } && ok "access token rotated" || no "access token not rotated"
{ [ -n "$RT2" ] && [ "$RT2" != "$RT" ]; } && ok "refresh token rotated" || no "refresh token not rotated"

echo "=== 7. PROTECTED ENDPOINT WITH THE REFRESHED TOKEN ==="
spend_auth
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/auth/me" -H "Authorization: Bearer $AT2")
check "refreshed access token reaches a protected endpoint" "$code" "200"

echo "=== 8. GEO DATA WITH THE REFRESHED SESSION (not rate limited) ==="
code=$(curl -s -o /tmp/ct.json -w '%{http_code}' "$API/api/v1/cities" -H "Authorization: Bearer $AT2")
check "cities list returns 200" "$code" "200"
n=$(python3 -c 'import json;print(len(json.load(open("/tmp/ct.json"))["data"]))' 2>/dev/null || echo 0)
[ "${n:-0}" -gt 0 ] && ok "$n seeded cities returned" || no "no seeded cities returned"
CID=$(jget /tmp/ct.json data 0 id)
code=$(curl -s -o /tmp/zn.json -w '%{http_code}' "$API/api/v1/cities/$CID/zones" -H "Authorization: Bearer $AT2")
check "zones for the first city return 200" "$code" "200"
z=$(python3 -c 'import json;print(len(json.load(open("/tmp/zn.json"))["data"]))' 2>/dev/null || echo 0)
[ "${z:-0}" -gt 0 ] && ok "$z seeded zones returned" || no "no seeded zones returned"
ZID=$(jget /tmp/zn.json data 0 id)
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/zones/$ZID/boundary" -H "Authorization: Bearer $AT2")
check "zone boundary returns 200" "$code" "200"
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/meta/platform")
check "platform meta is public" "$code" "200"

echo "=== 9. REFRESH-TOKEN REUSE IS REJECTED ==="
spend_auth
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/auth/refresh" \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}")
check "replaying a rotated refresh token is rejected" "$code" "401"
spend_auth
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/auth/refresh" \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT2\"}")
check "reuse revokes the whole token family" "$code" "401"

echo "=== 10. LOGOUT ==="
spend_auth
code=$(curl -s -o /tmp/li2.json -w '%{http_code}' -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
check "re-login after family revocation returns 200" "$code" "200"
RT3=$(jget /tmp/li2.json data refreshToken)
spend_auth
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/auth/logout" \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT3\"}")
{ [ "$code" = "200" ] || [ "$code" = "204" ]; } && ok "logout returns $code" || no "logout returned $code"
spend_auth
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/auth/refresh" \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT3\"}")
check "refresh token is dead after logout" "$code" "401"

echo "=== 11. RATE LIMITER ACTUALLY FIRES ==="
echo "  ...  waiting 62s for a clean window, then sending 13 logins"
sleep 62
limited=0
for i in $(seq 1 13); do
  c=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/auth/login" \
    -H 'Content-Type: application/json' -d '{"email":"nobody@citypulse.local","password":"wrong"}')
  [ "$c" = "429" ] && limited=$((limited+1))
done
[ "$limited" -gt 0 ] && ok "rate limiter returned 429 on $limited of 13 requests" || no "rate limiter never fired"
auth_used=99   # force a fresh window for anything after this

echo
echo "================================"
echo "  PASSED: $pass    FAILED: $fail"
echo "================================"
[ "$fail" -eq 0 ]
