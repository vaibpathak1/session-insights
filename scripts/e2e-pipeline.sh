#!/usr/bin/env bash
# Phase 4 pipeline e2e: SDK → collector → Kafka → event-processor → ClickHouse + object storage.
# Checks what the pipeline adds (ClickHouse, SeaweedFS, schema, event-processor), then runs
# scripts/e2e-sdk.sh (which checks Kafka, PostgreSQL, the collector and runs Playwright) with
# E2E_PIPELINE=1, so the privacy test also reads the stored rows and objects.
# Extra arguments are passed to `playwright test`.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { printf '\033[31m✘\033[0m %s\n' "$*" >&2; exit 1; }
ok() { printf '\033[32m✔\033[0m %s\n' "$*"; }

[ -f .env ] || fail "Missing .env — run: cp .env.example .env"
set -a; source .env; set +a

for service in clickhouse seaweedfs; do
  state=$(docker compose ps --format '{{.State}}' "$service" 2>/dev/null || true)
  [ "$state" = running ] || fail "compose service '$service' is not running — run: docker compose up -d && ./scripts/verify-stack.sh"
done
table=$(docker compose exec -T clickhouse clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" \
  -q "EXISTS TABLE ${CLICKHOUSE_DB}.replay_chunks" 2>/dev/null || true)
[ "$table" = 1 ] || fail "ClickHouse table replay_chunks missing — start api-service once to apply migrations: mvn -q -pl services/api-service spring-boot:run"
ok "compose stack (clickhouse, seaweedfs, ClickHouse schema V2)"

curl -fsS -m 3 http://localhost:8082/actuator/health 2>/dev/null | grep -q '"status":"UP"' \
  || fail "event-processor is not healthy on :8082 — start it: mvn -q -pl services/event-processor spring-boot:run"
ok "event-processor healthy on :8082"

export E2E_PIPELINE=1
exec ./scripts/e2e-sdk.sh "$@"
