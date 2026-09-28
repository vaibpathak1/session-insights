#!/bin/bash
# Declares every topic explicitly (auto-create is disabled). Idempotent.
# Naming: <domain>.<entity>.v<schema-major>  — see ADR-0004.
set -euo pipefail

BOOTSTRAP="kafka:19092"
P="${PARTITIONS:-6}"
KT=/opt/kafka/bin/kafka-topics.sh

create() {
  local topic=$1 partitions=$2; shift 2
  $KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
      --topic "$topic" --partitions "$partitions" --replication-factor 1 "$@"
  echo "ok: $topic ($partitions partitions)"
}

# Hot path: small structured events (clicks, errors, navigation). Key = sessionId.
create telemetry.events.v1        "$P" --config retention.ms=259200000
create telemetry.events.v1.dlt    1    --config retention.ms=1209600000

# Replay: rrweb chunks, larger messages. Key = sessionId. max.message.bytes applies to the
# zstd-compressed batch: the collector accepts chunks up to 16 MB of JSON and returns 413 when
# one does not fit even compressed (Phase 4b).
create replay.chunks.v1           "$P" --config retention.ms=259200000 --config max.message.bytes=4194304
create replay.chunks.v1.dlt       1    --config retention.ms=1209600000 --config max.message.bytes=4194304

# Session lifecycle (started / heartbeat / closed). Compacted: latest state per session.
create session.lifecycle.v1       "$P" --config cleanup.policy=compact

# Sessions selected for AI analysis (only flagged sessions, ADR-0005). Low volume.
create analysis.requests.v1       3    --config retention.ms=604800000
create analysis.requests.v1.dlt   1    --config retention.ms=1209600000

$KT --bootstrap-server "$BOOTSTRAP" --list
