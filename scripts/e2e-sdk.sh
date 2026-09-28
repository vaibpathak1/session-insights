#!/usr/bin/env bash
# Runs the browser SDK end-to-end privacy test (Playwright) against the local stack:
# checks compose + collector, rotates the dev site key, builds the SDK, runs the test.
# Extra arguments are passed to `playwright test`.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { printf '\033[31m✘\033[0m %s\n' "$*" >&2; exit 1; }
ok() { printf '\033[32m✔\033[0m %s\n' "$*"; }

[ -f .env ] || fail "Missing .env — run: cp .env.example .env"
command -v docker >/dev/null || fail "docker not found"
command -v curl >/dev/null || fail "curl not found"
command -v npm >/dev/null || fail "node/npm not found — install Node $(cat sdk/.nvmrc) (see sdk/.nvmrc)"
node_major=$(node -p 'process.versions.node.split(".")[0]')
[ "$node_major" -ge "$(cat sdk/.nvmrc)" ] \
  || fail "Node $(node -v) is too old — use Node $(cat sdk/.nvmrc) (e.g. 'fnm use' or 'nvm use' in sdk/)"

for service in kafka postgres; do
  state=$(docker compose ps --format '{{.State}}' "$service" 2>/dev/null || true)
  [ "$state" = running ] || fail "compose service '$service' is not running — run: docker compose up -d && ./scripts/verify-stack.sh"
done
for topic in telemetry.events.v1 replay.chunks.v1; do
  docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null \
    | grep -qx "$topic" || fail "Kafka topic $topic missing — run: docker compose up -d (kafka-init creates topics)"
done
ok "compose stack (kafka, postgres, topics)"

curl -fsS -m 3 http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"' \
  || fail "collector-service is not healthy on :8081 — start it: mvn -q -pl services/collector-service spring-boot:run"
ok "collector-service healthy on :8081"

if curl -s -o /dev/null -m 2 http://localhost:5173; then
  fail "port 5173 is in use — stop the running demo site; the test starts its own with the new key"
fi

./scripts/dev-site-key.sh >/dev/null || fail "could not rotate the dev site key (see message above)"
ok "dev site key rotated into examples/demo-site/.env.local"

(cd sdk && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && npm run -s build) >/dev/null \
  || fail "SDK build failed — run: cd sdk && npm ci && npm run build"
(cd examples/demo-site && { [ -d node_modules ] || npm ci --no-audit --no-fund; }) >/dev/null \
  || fail "demo site install failed — run: cd examples/demo-site && npm ci"
(cd sdk && npx playwright install chromium >/dev/null) || fail "could not install Playwright's Chromium"
ok "SDK built, demo site and Chromium ready"

cd sdk && exec npx playwright test "$@"
