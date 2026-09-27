#!/usr/bin/env bash
# Phase 0 exit check: every dependency is up AND usable, not just "container running".
set -uo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Missing .env — run: cp .env.example .env"; exit 1; }
set -a; source .env; set +a

pass=0; fail=0
check() {
  local name=$1; shift
  if out=$("$@" 2>&1); then
    printf "  \033[32m✔\033[0m %-38s %s\n" "$name" "$(echo "$out" | tail -n1 | cut -c1-60)"; pass=$((pass+1))
  else
    printf "  \033[31m✘\033[0m %-38s %s\n" "$name" "$(echo "$out" | tail -n1 | cut -c1-80)"; fail=$((fail+1))
  fi
}

echo "Kafka"
# Detail = broker line only, e.g. "localhost:9092 (id: 1 rack: null isFenced: false)".
check "broker reachable" bash -o pipefail -c "docker compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | grep -m1 -o '^.*(id: [^)]*)'"
for t in telemetry.events.v1 replay.chunks.v1 session.lifecycle.v1 analysis.requests.v1; do
  check "topic $t" sh -c "docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -x $t"
done

echo "PostgreSQL"
check "connection" docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select version()"
check "pgvector extension" sh -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc \"select extversion from pg_extension where extname='vector'\" | grep -E '[0-9]'"
check "vector math works" docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select '[1,2,3]'::vector <-> '[1,2,4]'::vector"

echo "ClickHouse"
check "query" docker compose exec -T clickhouse clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" -q "SELECT version()"
check "database $CLICKHOUSE_DB exists" sh -c "docker compose exec -T clickhouse clickhouse-client --user $CLICKHOUSE_USER --password $CLICKHOUSE_PASSWORD -q 'SHOW DATABASES' | grep -x $CLICKHOUSE_DB"

echo "Object storage (SeaweedFS S3)"
# --progress quiet keeps Compose's "Container … Creating" lines out of the detail column.
head_bucket() {
  docker compose --progress quiet run --rm -T --entrypoint aws \
    -e AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" -e AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
    storage-init --endpoint-url http://seaweedfs:8333 s3api head-bucket --bucket "$S3_REPLAY_BUCKET" \
    && echo "exists (HeadBucket OK)"
}
check "bucket $S3_REPLAY_BUCKET" head_bucket

echo "Ollama ($OLLAMA_BASE_URL — native on macOS, or --profile ollama-docker)"
check "server reachable" curl -sf "$OLLAMA_BASE_URL/api/version"
has_model() { curl -sf "$OLLAMA_BASE_URL/api/tags" | python3 -c "import sys,json; m=[x['name'] for x in json.load(sys.stdin)['models']]; want='$1'; ok=any(n==want or n==want+':latest' for n in m); print('found' if ok else 'missing; installed: '+', '.join(m)); sys.exit(0 if ok else 1)"; }
check "chat model $OLLAMA_CHAT_MODEL" has_model "$OLLAMA_CHAT_MODEL"
check "embedding model $OLLAMA_EMBEDDING_MODEL" has_model "$OLLAMA_EMBEDDING_MODEL"
check "embedding returns 768 dims" sh -c "curl -sf $OLLAMA_BASE_URL/api/embed -d '{\"model\":\"$OLLAMA_EMBEDDING_MODEL\",\"input\":\"hello\"}' | python3 -c 'import sys,json; d=len(json.load(sys.stdin)[\"embeddings\"][0]); print(d); sys.exit(0 if d==768 else 1)'"
check "tool-calling chat responds" sh -c "curl -sf $OLLAMA_BASE_URL/api/chat -d '{\"model\":\"$OLLAMA_CHAT_MODEL\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"Flag session s-1 for review\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"flagForHumanReview\",\"description\":\"Escalate a session to a human analyst\",\"parameters\":{\"type\":\"object\",\"properties\":{\"sessionId\":{\"type\":\"string\"}},\"required\":[\"sessionId\"]}}}]}' | python3 -c 'import sys,json; m=json.load(sys.stdin)[\"message\"]; c=m.get(\"tool_calls\"); print(\"tool_call:\", c[0][\"function\"][\"name\"]) if c else print(\"no tool call (model answered in text)\"); sys.exit(0 if c else 1)'"

echo
echo "Passed: $pass   Failed: $fail"
[ "$fail" -eq 0 ] && echo "Phase 0 stack is ready." || echo "Fix the failures above before Phase 1."
exit "$fail"
