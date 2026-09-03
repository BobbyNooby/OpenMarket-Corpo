#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# auth flow-test — one long, comprehensive run against the LIVE service.
#
# Boots its own isolated stack (throwaway Postgres on :5434, fake Discord on
# :5399, real Spring Boot app on :8080), then walks the entire API contract
# step by step, asserting status codes AND response bodies — the things mocks
# can't prove: Flyway migrations, Hibernate validation, real bcrypt, real
# RS256 keys, real transaction semantics (rotation reuse → family revoke).
#
# Usage:  services/auth/scripts/flow-test.sh
# Exit 0 = every step green.
# ─────────────────────────────────────────────────────────────────────────────
set -u
cd "$(dirname "$0")/.."   # services/auth

TMP="${TMPDIR:-/tmp}/auth-flow-test"
rm -rf "$TMP"; mkdir -p "$TMP"
PASS=0; FAIL=0

step() { # step <name> <expected-status> [curl args...]
  local name="$1" expected="$2"; shift 2
  local status
  status=$(curl -s -o "$TMP/body" -w "%{http_code}" "$@")
  if [ "$status" = "$expected" ]; then
    echo "  ok  $name"; PASS=$((PASS+1)); return 0
  fi
  echo "  FAIL $name — expected $expected, got $status"; sed 's/^/       /' "$TMP/body" | head -3
  FAIL=$((FAIL+1)); return 1
}

body_has() { # body_has <jq-less python expr over d>
  python3 -c "
import json,sys
try: d=json.load(open('$TMP/body'))
except Exception: sys.exit(1)
sys.exit(0 if ($1) else 1)
"
}

expect() { # expect <name> <python expr>  (asserts on last response body)
  if body_has "$2"; then echo "  ok  $1"; PASS=$((PASS+1)); else
    echo "  FAIL $1 — body assertion: $2"; sed 's/^/       /' "$TMP/body" | head -3; FAIL=$((FAIL+1))
  fi
}

API="http://localhost:8080"
PG_CONTAINER=om-auth-flow-pg

cleanup() {
  kill "${APP_PID:-}" "${GATEWAY_PID:-}" "${CATALOGUE_PID:-}" 2>/dev/null
  # the app IS the jar JVM now (APP_PID), but be thorough: Boot shutdown can
  # outlive a SIGTERM, and an orphaned :8080 makes the next run test a ghost
  sleep 1
  kill -9 "${APP_PID:-}" "${GATEWAY_PID:-}" "${CATALOGUE_PID:-}" 2>/dev/null
  lsof -ti :8080 -ti :3000 -ti :8081 2>/dev/null | xargs kill -9 2>/dev/null
  docker rm -f "$PG_CONTAINER" >/dev/null 2>&1
  pkill -f fake-discord.py 2>/dev/null
}
trap cleanup EXIT

# pre-flight: a stale app on these ports makes the wait-loop mistake a ghost
# for this run's app — every assertion would then test yesterday's build
if lsof -ti :8080 -ti :3000 -ti :5434 >/dev/null 2>&1; then
  echo "ports 8080/3000/5434 busy — kill the stale processes first"; exit 1
fi

echo "── booting isolated stack ──────────────────────────────────"
docker run -d --rm --name "$PG_CONTAINER" -e POSTGRES_USER=om -e POSTGRES_PASSWORD=devpassword123 \
  -e POSTGRES_DB=auth_db -p 5434:5432 postgres:17 >/dev/null || { echo "postgres failed"; exit 1; }
python3 scripts/fake-discord.py >/dev/null 2>&1 &
sleep 2

# env for the app — MUST be set before launch
export POSTGRES_PORT=5434
export DISCORD_CLIENT_ID=fake-client-id
export DISCORD_CLIENT_SECRET=fake-client-secret
export DISCORD_AUTHORIZE_URL=http://localhost:5399/oauth2/authorize
export DISCORD_TOKEN_URL=http://localhost:5399/api/oauth2/token
export DISCORD_USERS_ME_URL=http://localhost:5399/api/users/@me
# §10 greps token links from the dev mail log — full-body logging must stay on
export AUTH_MAIL_LOG_FULL=true

# Boot 4's spring-boot:run no longer propagates shell env (or its old
# jvmArguments switch reliably) to the forked app JVM — the app would fall
# back to application.yml defaults (compose's :5432 Postgres) and the whole
# test would silently run against the WRONG database. Run the packaged jar
# with explicit system properties instead: Spring reads -D system
# properties first, so the app is pinned to THIS script's throwaway stack.
mvn -q -DskipTests package
java -DPOSTGRES_PORT=5434 \
  -DDISCORD_CLIENT_ID=fake-client-id \
  -DDISCORD_CLIENT_SECRET=fake-client-secret \
  -DDISCORD_AUTHORIZE_URL=http://localhost:5399/oauth2/authorize \
  -DDISCORD_TOKEN_URL=http://localhost:5399/api/oauth2/token \
  -DDISCORD_USERS_ME_URL=http://localhost:5399/api/users/@me \
  -Dauth.mail.log-full=true -Dlogging.level.org.hibernate.SQL=DEBUG -Dlogging.level.org.hibernate.orm.jdbc.bind=TRACE \
  -jar target/auth-0.1.0.jar >"$TMP/app.log" 2>&1 &
APP_PID=$!
for i in $(seq 1 90); do sleep 2; curl -sf -m 2 "$API/health/live" >/dev/null 2>&1 && break; done
step "app is up" 200 "$API/health/live"
# ground truth: the app must be talking to THIS run's throwaway DB
DBUSERS=$(docker exec "$PG_CONTAINER" psql -U om -d auth_db -t -c 'SELECT count(*) FROM auth.users;' 2>/dev/null | tr -d ' ')
echo "  db sanity: users rows at boot = ${DBUSERS:-<unreachable>}"
if [ "${DBUSERS:-1}" != "0" ]; then
  echo "  FATAL: throwaway DB is not fresh — aborting instead of testing a ghost"
  docker exec "$PG_CONTAINER" psql -U om -d auth_db -c 'SELECT email FROM auth.users;' 2>/dev/null | head -8
  exit 1
fi

echo "── §1 infrastructure ───────────────────────────────────────"
step "GET / service info" 200 "$API/"
expect "service name is auth" "d['service']=='auth'"
step "GET /health/live" 200 "$API/health/live"
step "GET /health/ready (Flyway ran, Postgres reachable)" 200 "$API/health/ready"
step "GET /.well-known/jwks.json" 200 "$API/.well-known/jwks.json"
expect "jwks exposes RSA key with kid" "d['keys'][0]['kty']=='RSA' and d['keys'][0]['kid']"
expect "jwks leaks no private material" "'d' not in d['keys'][0] and 'p' not in d['keys'][0]"

echo "── §2 registration ─────────────────────────────────────────"
step "register lux → 201" 201 -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"email":"lux@demaciabook.com","password":"Crownguard22","name":"Luxanna Crownguard","username":"lux"}'
expect "register returns the first user as platform owner" "d['user']['roles']==['owner'] and d['user']['email']=='lux@demaciabook.com'"
step "duplicate email → 409 email_taken" 409 -X POST "$API/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"email":"lux@demaciabook.com","password":"Crownguard22","name":"Lux"}'
expect "conflict code is email_taken + field" "d['code']=='email_taken' and d['field']=='email'"
step "invalid body → 400 validation_failed" 400 -X POST "$API/api/v1/auth/register" \
  -H 'Content-Type: application/json' -d '{"email":"nope","password":"x","name":""}'
expect "validation error names a field" "d['code']=='validation_failed' and d['field'] is not None"
step "malformed json → 400" 400 -X POST "$API/api/v1/auth/register" \
  -H 'Content-Type: application/json' -d '{nope'

echo "── §3 login ────────────────────────────────────────────────"
step "login wrong password → 401" 401 -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@demaciabook.com","password":"wrongpass99"}'
expect "wrong password code" "d['code']=='invalid_credentials'"
step "login unknown email → 401" 401 -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"sylas@mage-underground.org","password":"whatever1"}'
expect "unknown email is INDISTINGUISHABLE from wrong password" "d['code']=='invalid_credentials'"
step "login lux → 200 + cookies" 200 -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@demaciabook.com","password":"Crownguard22"}'
expect "cookies were set" "True" # verified implicitly: next step uses them

echo "── §4 profile ──────────────────────────────────────────────"
step "GET /users/me" 200 -b "$TMP/cj-lux.txt" "$API/api/v1/users/me"
expect "identity from token: email + owner role + loginMethods" \
  "d['email']=='lux@demaciabook.com' and d['roles']==['owner'] and d['loginMethods']['password'] is True and d['loginMethods']['providers']==[]"
step "PATCH /users/me → 200" 200 -b "$TMP/cj-lux.txt" -X PATCH "$API/api/v1/users/me" \
  -H 'Content-Type: application/json' \
  -d '{"bio":"Light mage (allegedly)","accentColor":"#c8aa6e","socialLinks":{"discord":"lux"}}'
expect "patch applied + jsonb maps round-trip" "d['profile']['bio']=='Light mage (allegedly)' and d['profile']['accentColor']=='#c8aa6e' and d['profile']['socialLinks']['discord']=='lux'"
step "PATCH invalid accentColor → 400" 400 -b "$TMP/cj-lux.txt" -X PATCH "$API/api/v1/users/me" \
  -H 'Content-Type: application/json' -d '{"accentColor":"gold"}'

echo "── §5 sessions ─────────────────────────────────────────────"
step "GET /auth/sessions" 200 -b "$TMP/cj-lux.txt" "$API/api/v1/auth/sessions"
expect "register + login = 2 live families, newest flagged current, device metadata present" \
  "len(d)==2 and d[0]['current'] is True and d[1]['current'] is False and d[0]['userAgent'] is not None and d[0]['ipAddress']"

echo "── §6 refresh rotation + theft detection ───────────────────"
cp "$TMP/cj-lux.txt" "$TMP/cj-old.txt"
step "refresh #1 (rotation)" 200 -b "$TMP/cj-lux.txt" -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/refresh"
step "refresh #2 (chain alive)" 200 -b "$TMP/cj-lux.txt" -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/refresh"
OLD_REFRESH=$(grep om_refresh "$TMP/cj-old.txt" | awk '{print $7}')
step "REPLAY old refresh token → 401 reused" 401 -b "om_refresh=$OLD_REFRESH" -X POST "$API/api/v1/auth/refresh"
expect "reuse error code" "d['code']=='refresh_token_reused'"
step "newest token is dead too (family revoked) → 401" 401 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/refresh"

echo "── §7 password credentials ─────────────────────────────────"
step "re-login" 200 -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@demaciabook.com","password":"Crownguard22"}'
step "add password when one exists → 409" 409 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/credentials" \
  -H 'Content-Type: application/json' -d '{"password":"whatever123"}'
expect "password_exists code" "d['code']=='password_exists'"
step "change password with wrong current → 401" 401 -b "$TMP/cj-lux.txt" -X PATCH "$API/api/v1/auth/credentials" \
  -H 'Content-Type: application/json' -d '{"currentPassword":"wrongpass99","newPassword":"NewPass12345"}'
step "change password → 204" 204 -b "$TMP/cj-lux.txt" -X PATCH "$API/api/v1/auth/credentials" \
  -H 'Content-Type: application/json' -d '{"currentPassword":"Crownguard22","newPassword":"NewPass12345"}'
step "old password no longer works → 401" 401 -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@demaciabook.com","password":"Crownguard22"}'
step "new password works → 200" 200 -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@demaciabook.com","password":"NewPass12345"}'
step "unlink discord when not linked → 404" 404 -b "$TMP/cj-lux.txt" -X DELETE "$API/api/v1/auth/connections/discord"
expect "provider_not_linked code" "d['code']=='provider_not_linked'"
step "remove password with no oauth → 409 last method" 409 -b "$TMP/cj-lux.txt" -X DELETE "$API/api/v1/auth/credentials" \
  -H 'Content-Type: application/json' -d '{"currentPassword":"NewPass12345"}'
expect "last_login_method code" "d['code']=='last_login_method'"

echo "── §8 discord oauth (against the FAKE discord api) ─────────"
# sign-up/link flow of a Discord-first user
LOCATION=$(curl -s -o /dev/null -w "%{redirect_url}" -c "$TMP/cj-gd-pre.txt" "$API/api/v1/auth/discord")
if [ -n "$LOCATION" ]; then echo "  ok  GET /auth/discord → 302 to authorize URL"; PASS=$((PASS+1)); else echo "  FAIL no redirect"; FAIL=$((FAIL+1)); fi
STATE=$(python3 -c "from urllib.parse import urlparse,parse_qs; print(parse_qs(urlparse('$LOCATION').query)['state'][0])")
step "callback exchanges code at fake Discord → 302 success" 302 -c "$TMP/cj-gd.txt" \
  -b "om_oauth=$STATE" "$API/api/v1/auth/discord/callback?code=fake-code&state=$STATE"
step "GET /users/me (discord-first user)" 200 -b "$TMP/cj-gd.txt" "$API/api/v1/users/me"
expect "discord signup: verified email, providers=[discord], no password yet" \
  "d['email']=='garen@demaciabook.com' and d['emailVerified'] is True and d['loginMethods']=={'password': False, 'providers': ['discord']}"
expect "discord profile auto-provisioned" "d['profile']['username'].startswith('garen-crownguard') or d['profile']['username'].startswith('garen')"
step "add password to discord-first account → 201" 201 -b "$TMP/cj-gd.txt" -X POST "$API/api/v1/auth/credentials" \
  -H 'Content-Type: application/json' -d '{"password":"Demacia4Ever22"}'
step "password login now works for discord user → 200" 200 -c "$TMP/cj-gd.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"garen@demaciabook.com","password":"Demacia4Ever22"}'
# lux tries to link the SAME discord account → provider_already_linked
LLOC=$(curl -s -o /dev/null -w "%{redirect_url}" -b "$TMP/cj-lux.txt" -c "$TMP/cj-lux-link.txt" "$API/api/v1/auth/discord/link")
LSTATE=$(python3 -c "from urllib.parse import urlparse,parse_qs; print(parse_qs(urlparse('$LLOC').query)['state'][0])")
FAILLOC=$(curl -s -o /dev/null -w "%{redirect_url}" -b "$TMP/cj-lux.txt" -b "om_oauth=$LSTATE" \
  "$API/api/v1/auth/discord/callback?code=fake-code&state=$LSTATE")
if [ "$FAILLOC" = "http://localhost:3000/auth/failure?error=provider_already_linked" ]; then
  echo "  ok  link foreign discord → failure redirect error=provider_already_linked"; PASS=$((PASS+1))
else echo "  FAIL redirect was: $FAILLOC"; FAIL=$((FAIL+1)); fi
step "unlink discord (password remains) → 204" 204 -b "$TMP/cj-gd.txt" -X DELETE "$API/api/v1/auth/connections/discord"
step "unlink again → 404 provider_not_linked" 404 -b "$TMP/cj-gd.txt" -X DELETE "$API/api/v1/auth/connections/discord"
step "password still works after unlink → 200" 200 -o /dev/null -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"garen@demaciabook.com","password":"Demacia4Ever22"}'

echo "── §9 logout (account deletion moves to the final section) ─"
step "logout (no access token needed) → 204" 204 -b "$TMP/cj-lux.txt" -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/logout"
step "cookies cleared (refresh dead) → 401" 401 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/refresh"
step "login for the remaining sections" 200 -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@demaciabook.com","password":"NewPass12345"}'

echo "── §10 email flows (dev mail log carries the tokens) ───────"
# NOTE: lux was the FIRST registered user → bootstrap made them 'owner'
step "resend verification → 204" 204 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/verify-email/resend"
sleep 1 # app log flush
VERIFY_TOKEN=$(grep -o 'verify-email?token=[A-Za-z0-9_-]*' "$TMP/app.log" | tail -1 | cut -d= -f2)
[ -n "$VERIFY_TOKEN" ] && { echo "  ok  verification token captured from dev mail log"; PASS=$((PASS+1)); } || { echo "  FAIL no token in mail log"; FAIL=$((FAIL+1)); }
step "verify with e-mailed token → 204" 204 -X POST "$API/api/v1/auth/verify-email" \
  -H 'Content-Type: application/json' -d "{\"token\":\"$VERIFY_TOKEN\"}"
step "GET /users/me shows emailVerified" 200 -b "$TMP/cj-lux.txt" "$API/api/v1/users/me"
expect "email now verified" "d['emailVerified'] is True"
step "resend again → 409 email_already_verified" 409 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/verify-email/resend"
step "email change → 202 verification_sent" 202 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/email/change" \
  -H 'Content-Type: application/json' -d '{"newEmail":"lux@crownguard.house"}'
sleep 1 # app log flush
CHANGE_TOKEN=$(grep -o 'verify-email?token=[A-Za-z0-9_-]*' "$TMP/app.log" | tail -1 | cut -d= -f2)
sleep 1 # let the app log flush
step "confirm email change with e-mailed token → 204" 204 -X POST "$API/api/v1/auth/verify-email" \
  -H 'Content-Type: application/json' -d "{\"token\":\"$CHANGE_TOKEN\"}"
step "login with the NEW email → 200" 200 -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@crownguard.house","password":"NewPass12345"}'
step "forgot-password for unknown email → 204 (never reveals)" 204 -X POST "$API/api/v1/auth/forgot-password" \
  -H 'Content-Type: application/json' -d '{"email":"sylas@mage-underground.org"}'
step "forgot-password → 204" 204 -X POST "$API/api/v1/auth/forgot-password" \
  -H 'Content-Type: application/json' -d '{"email":"lux@crownguard.house"}'
# delivery is now async (post-commit) — poll for the log line instead of a
# single fixed sleep, which flakes when the executor pickup is slow
RESET_TOKEN=""
for i in $(seq 1 10); do
  RESET_TOKEN=$(grep -o 'reset-password?token=[A-Za-z0-9_-]*' "$TMP/app.log" | tail -1 | cut -d= -f2)
  [ -n "$RESET_TOKEN" ] && break
  sleep 1
done
step "reset-password with e-mailed token → 204" 204 -X POST "$API/api/v1/auth/reset-password" \
  -H 'Content-Type: application/json' -d "{\"token\":\"$RESET_TOKEN\",\"newPassword\":\"LuxReset12345\"}"
step "old password dead after reset → 401" 401 -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@crownguard.house","password":"NewPass12345"}'
step "new password works → 200" 200 -c "$TMP/cj-lux.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@crownguard.house","password":"LuxReset12345"}'
# rate limit: 5/hour per email+ip — hammer a fresh address
RL_STATUS=200
for i in 1 2 3 4 5 6; do
  RL_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/api/v1/auth/forgot-password" \
    -H 'Content-Type: application/json' -d '{"email":"hammer@demaciabook.com"}')
done
if [ "$RL_STATUS" = "429" ]; then echo "  ok  forgot-password rate limit hits 429"; PASS=$((PASS+1)); else echo "  FAIL expected 429, got $RL_STATUS"; FAIL=$((FAIL+1)); fi

echo "── §11 admin & moderation (lux = owner via bootstrap) ──────"
GAREN_ID=$(curl -s -b "$TMP/cj-gd.txt" "$API/api/v1/users/me" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
step "GET /admin/users as owner → 200" 200 -b "$TMP/cj-lux.txt" "$API/api/v1/admin/users"
expect "paged result carries users" "d['total'] >= 2 and len(d['items']) >= 2"
step "GET /admin/users as plain user → 403 forbidden" 403 -b "$TMP/cj-gd.txt" "$API/api/v1/admin/users"
expect "forbidden envelope code" "d['code']=='forbidden'"
step "GET /admin/users/{id} detail → 200" 200 -b "$TMP/cj-lux.txt" "$API/api/v1/admin/users/$GAREN_ID"
expect "detail carries roles and empty moderation history" "d['roles']==['user'] and d['bans']==[] and d['warnings']==[]"
step "warn garen → 201" 201 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/admin/users/$GAREN_ID/warn" \
  -H 'Content-Type: application/json' -d '{"reason":"selling cursed items"}'
expect "warning response echoes reason" "d['reason']=='selling cursed items'"
step "ban garen → 201" 201 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/admin/users/$GAREN_ID/ban" \
  -H 'Content-Type: application/json' -d '{"reason":"map hacking"}'
step "banned user login → 403 account_banned" 403 -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"garen@demaciabook.com","password":"Demacia4Ever22"}'
expect "ban error code" "d['code']=='account_banned'"
step "ban again → 409 already_banned" 409 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/admin/users/$GAREN_ID/ban" \
  -H 'Content-Type: application/json' -d '{"reason":"still hacking"}'
step "unban garen → 204" 204 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/admin/users/$GAREN_ID/unban"
step "login after unban → 200" 200 -c "$TMP/cj-gd.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"garen@demaciabook.com","password":"Demacia4Ever22"}'
step "PATCH roles garen [user,moderator] → 200" 200 -b "$TMP/cj-lux.txt" -X PATCH "$API/api/v1/admin/users/$GAREN_ID/roles" \
  -H 'Content-Type: application/json' -d '{"roles":["user","moderator"]}'
expect "new roles returned" "d['roles']==['user','moderator']"
step "PATCH roles with unknown role → 400" 400 -b "$TMP/cj-lux.txt" -X PATCH "$API/api/v1/admin/users/$GAREN_ID/roles" \
  -H 'Content-Type: application/json' -d '{"roles":["noxus"]}'
step "garen re-logs in to pick up the new roles → 200" 200 -c "$TMP/cj-gd.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"garen@demaciabook.com","password":"Demacia4Ever22"}'
step "garen (now moderator, fresh token) can list users → 200" 200 -b "$TMP/cj-gd.txt" "$API/api/v1/admin/users"
step "register teemo → 201" 201 -c "$TMP/cj-teemo.txt" -X POST "$API/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"email":"teemo@demaciabook.com","password":"YordleSnipes99","name":"Teemo"}'
step "login teemo → 200 (token minted BEFORE his ban)" 200 -c "$TMP/cj-teemo.txt" -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"teemo@demaciabook.com","password":"YordleSnipes99"}'
TEEMO_ID=$(curl -s -b "$TMP/cj-teemo.txt" "$API/api/v1/users/me" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
step "owner bans teemo → 201 Created (his token stays cryptographically valid)" 201 -b "$TMP/cj-lux.txt" \
  -X POST "$API/api/v1/admin/users/$TEEMO_ID/ban" -H 'Content-Type: application/json' \
  -d '{"reason":"edge propagation test"}'
step "export garen → 200" 200 -b "$TMP/cj-lux.txt" "$API/api/v1/admin/users/$GAREN_ID/export"
expect "export carries the auth slice" "d['user']['email']=='garen@demaciabook.com' and 'warnings' in d"
step "register janna → 201" 201 -c "$TMP/cj-janna.txt" -X POST "$API/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"email":"janna@demaciabook.com","password":"WindForce123","name":"Janna"}'
JOANNA_ID=$(curl -s -b "$TMP/cj-janna.txt" "$API/api/v1/users/me" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
step "erase janna (owner only) → 202" 202 -b "$TMP/cj-lux.txt" -X POST "$API/api/v1/admin/users/$JOANNA_ID/erase"
step "janna login after erase → 401" 401 -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"janna@demaciabook.com","password":"WindForce123"}'
step "erase as non-owner (garen moderator) → 403" 403 -b "$TMP/cj-gd.txt" -X POST "$API/api/v1/admin/users/$GAREN_ID/erase"

echo "── §12 access control ──────────────────────────────────"
step "me without token → 401" 401 "$API/api/v1/users/me"
step "sessions without token → 401" 401 "$API/api/v1/auth/sessions"
step "link without token → 401" 401 "$API/api/v1/auth/discord/link"
step "admin without token → 401" 401 "$API/api/v1/admin/users"
step "start flow is public → 302" 302 "$API/api/v1/auth/discord"
step "callback is public (bad state) → 302" 302 "$API/api/v1/auth/discord/callback?code=x&state=forged"

echo "── §13 account deletion (final) ────────────────────────────"
step "DELETE /users/me → 204" 204 -b "$TMP/cj-lux.txt" -X DELETE "$API/api/v1/users/me"
step "login after soft delete → 401" 401 -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"lux@crownguard.house","password":"LuxReset12345"}'
step "me after delete → 404 user_not_found (token still parses, identity is gone)" 404 -b "$TMP/cj-lux.txt" "$API/api/v1/users/me"
expect "  …and it is the user_not_found code" "d['code']=='user_not_found'"

echo "── §14 gateway: the real entry point ───────────────────────"
# The frontend never talks to :8080 — everything rides the gateway, which
# edge-authenticates via the IntrospectToken gRPC and proxies REST to auth.
# This section IS the first protobuf integration test.
(cd ../gateway && go build -o "$TMP/gateway" .) || { echo "gateway build failed"; exit 1; }
PORT=3000 AUTH_URL=http://localhost:8080 AUTH_GRPC_URL=localhost:9090 \
  "$TMP/gateway" >"$TMP/gateway.log" 2>&1 &
GATEWAY_PID=$!
booted=""
for i in $(seq 1 15); do
  grep -q "gateway listening" "$TMP/gateway.log" 2>/dev/null && { booted=1; break; }
  sleep 1
done
[ -n "$booted" ] || { echo "gateway failed to boot (port 3000 busy or crashed?)"; tail -5 "$TMP/gateway.log" 2>/dev/null; exit 1; }
for i in $(seq 1 15); do sleep 1; curl -sf -m 2 "http://localhost:3000/health/live" >/dev/null 2>&1 && break; done
API_GW="http://localhost:3000"

step "gateway health/live → 200" 200 "$API_GW/health/live"
step "unknown /api route → 404 (not the root info JSON)" 404 "$API_GW/api/v1/definitely-not-a-thing"
expect "  …with the not_found code" "d['code']=='not_found'"
docker exec $PG_CONTAINER psql -U om -d postgres -c 'CREATE DATABASE catalogue_db' >/dev/null 2>&1 || true
(cd ../catalogue && dotnet publish -c Release -o "$TMP/catalogue" >/dev/null) || { echo "catalogue build failed"; exit 1; }
# this script IS a dev environment — say so, or catalogue's Production
# fail-fast (dev-default secrets) refuses to start
DATABASE_URL="postgres://om:devpassword123@localhost:5434/catalogue_db" \
DATABASE_SSLMODE=disable AUTH_URL=http://localhost:8080 \
AUTH_GRPC_URL=http://localhost:9090 GRPC_INTERNAL_SECRET=dev-internal-secret \
ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS=http://localhost:8081 \
nohup "$TMP/catalogue/Catalogue" >"$TMP/catalogue.log" 2>&1 &
CATALOGUE_PID=$!
for i in $(seq 1 20); do sleep 1; curl -sf -m 2 http://localhost:8081/health/ready >/dev/null 2>&1 && break; done
step "catalogue browse through gateway → 200 (live upstream)" 200 "$API_GW/api/v1/catalogue/items"
step "register through gateway → 201" 201 -c "$TMP/cj-gw.txt" -X POST "$API_GW/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"email":"heimer@demaciabook.com","password":"PoroRocket77","name":"Heimerdinger"}'
step "login through gateway → 200" 200 -c "$TMP/cj-gw.txt" -X POST "$API_GW/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"email":"heimer@demaciabook.com","password":"PoroRocket77"}'
step "me via gateway (cookie → introspect → proxy) → 200" 200 -b "$TMP/cj-gw.txt" "$API_GW/api/v1/users/me"
expect "  …identity round-trips" "d['email']=='heimer@demaciabook.com'"
step "forged Bearer through gateway → 401 at the EDGE" 401 -H "Authorization: Bearer forged-token" "$API_GW/api/v1/users/me"
step "spoofed X-Forwarded-For does not break the chain → 200" 200 -b "$TMP/cj-gw.txt" \
  -H "X-Forwarded-For: 6.6.6.6" "$API_GW/api/v1/users/me"
step "refresh through gateway (path-scoped cookie survives) → 200" 200 -b "$TMP/cj-gw.txt" -c "$TMP/cj-gw.txt" \
  -X POST "$API_GW/api/v1/auth/refresh"
step "me with the rotated cookie still → 200" 200 -b "$TMP/cj-gw.txt" "$API_GW/api/v1/users/me"
step "sessions through gateway → 200" 200 -b "$TMP/cj-gw.txt" "$API_GW/api/v1/auth/sessions"

# ── edge ban propagation: the one thing ONLY the edge can enforce ──
# auth's /users/me re-validates the token cryptographically but does NOT
# re-check bans — teemo's token is cryptographically valid (minted pre-ban),
# so a 401 here can only come from the gateway's DB-backed introspection.
step "banned user's pre-ban token through gateway → 401 at the edge" 401 -b "$TMP/cj-teemo.txt" "$API_GW/api/v1/users/me"

echo "── §15 catalogue: items, listings, trades (via gateway) ────"
step "catalogue health/ready → 200 (migrations + seed)" 200 http://localhost:8081/health/ready
# lux self-deleted in §13 — BUT §14 already registered heimer through the
# gateway, so the owner bootstrap went to HEIMER, not to anyone registering
# below. Admin ops use heimer (owner); flow-admin is the plain user used for
# the negative (403) case.
step "register flow-admin → 201 (plain user; owner went to §14's heimer)" 201 -c "$TMP/cj-admin.txt" -X POST "$API_GW/api/v1/auth/register" \
  -H 'Content-Type: application/json' -d '{"email":"flow-admin@demaciabook.com","password":"FlowAdmin123","name":"Flow Admin"}'
step "heimer (owner) creates currency → 201" 201 -b "$TMP/cj-gw.txt" -X POST "$API_GW/api/v1/catalogue/currencies" \
  -H 'Content-Type: application/json' -d '{"name":"Flow Crowns"}'
FLOW_COIN_ID=$(curl -s -b "$TMP/cj-gw.txt" "$API_GW/api/v1/catalogue/currencies" | python3 -c "import json,sys; print([c['id'] for c in json.load(sys.stdin)['currencies'] if c['slug']=='flow-crowns'][0])")
step "heimer (owner) creates item → 201" 201 -b "$TMP/cj-gw.txt" -X POST "$API_GW/api/v1/catalogue/items" \
  -H 'Content-Type: application/json' -d '{"name":"Flow Blade","categorySlug":"weapons"}'
FLOW_ITEM_ID=$(curl -s -b "$TMP/cj-gw.txt" "$API_GW/api/v1/catalogue/items" | python3 -c "import json,sys; print([i['id'] for i in json.load(sys.stdin)['items'] if i['slug']=='flow-blade'][0])")
step "heimer posts buy listing (requests item, offers crowns) → 201" 201 -b "$TMP/cj-gw.txt" -X POST "$API_GW/api/v1/catalogue/listings" \
  -H 'Content-Type: application/json' \
  -d "{\"requestedItemId\":\"$FLOW_ITEM_ID\",\"amount\":2,\"orderType\":\"buy\",\"payingType\":\"each\",\"offered\":[{\"kind\":\"currency\",\"id\":\"$FLOW_COIN_ID\",\"amount\":5}]}"
step "heimer's listing list → 200" 200 -b "$TMP/cj-gw.txt" "$API_GW/api/v1/catalogue/listings/me/listings"
H_LISTING_ID=$(curl -s -b "$TMP/cj-gw.txt" "$API_GW/api/v1/catalogue/listings/me/listings" | python3 -c "import json,sys; print(json.load(sys.stdin)['listings'][0]['id'])")
step "browse listings by requested item → 200" 200 "$API_GW/api/v1/catalogue/listings?requestedItemId=$FLOW_ITEM_ID"
step "heimer pauses → 200" 200 -b "$TMP/cj-gw.txt" -X POST "$API_GW/api/v1/catalogue/listings/$H_LISTING_ID/pause"
step "heimer resumes → 200" 200 -b "$TMP/cj-gw.txt" -X POST "$API_GW/api/v1/catalogue/listings/$H_LISTING_ID/resume"
step "garen (seller) accepts heimer's buy listing → 201 trade" 201 -b "$TMP/cj-gd.txt" -X POST "$API_GW/api/v1/catalogue/listings/$H_LISTING_ID/accept" \
  -H 'Idempotency-Key: flow-trade-1'
step "double accept → 409" 409 -b "$TMP/cj-gd.txt" -X POST "$API_GW/api/v1/catalogue/listings/$H_LISTING_ID/accept" \
  -H 'Idempotency-Key: flow-trade-2'
step "heimer sees the trade → 200" 200 -b "$TMP/cj-gw.txt" "$API_GW/api/v1/catalogue/listings/me/trades"
step "heimer watchlist add → 200" 200 -b "$TMP/cj-gw.txt" -X PUT "$API_GW/api/v1/catalogue/me/watchlist/$H_LISTING_ID"
step "heimer watchlist remove → 200" 200 -b "$TMP/cj-gw.txt" -X DELETE "$API_GW/api/v1/catalogue/me/watchlist/$H_LISTING_ID"
step "plain user (flow-admin) cannot create items → 403" 403 -b "$TMP/cj-admin.txt" -X POST "$API_GW/api/v1/catalogue/items" \
  -H 'Content-Type: application/json' -d '{"name":"Sneaky Item"}'
kill "$CATALOGUE_PID" "$GATEWAY_PID" 2>/dev/null

echo "────────────────────────────────────────────────────────────"
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
