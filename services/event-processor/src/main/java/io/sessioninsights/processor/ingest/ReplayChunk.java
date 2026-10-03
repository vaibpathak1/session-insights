package io.sessioninsights.processor.ingest;

import io.sessioninsights.events.ReplayObjects;
import com.github.luben.zstd.Zstd;
import io.sessioninsights.common.wire.ReplayEnvelope;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.events.ManifestRow;
import io.sessioninsights.processor.store.ReplayObjectStore;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * A validated chunk ready to store: the rrweb event array as JSON, zstd-compressed, and its
 * manifest row. The object holds exactly the array the SDK sent (masked in the browser).
 */
record ReplayChunk(ReplayEnvelope envelope, byte[] compressed, ManifestRow manifest) {

    /** rrweb {@code EventType.FullSnapshot}. */
    static final int FULL_SNAPSHOT = 2;

    static ReplayChunk of(ReplayEnvelope envelope, int zstdLevel) {
        JsonNode events = envelope.events();
        byte[] compressed = Zstd.compress(WireJson.mapper().writeValueAsBytes(events), zstdLevel);
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        boolean fullSnapshot = false;
        for (JsonNode event : events) {
            long ts = event.path("timestamp").asLong();
            first = Math.min(first, ts);
            last = Math.max(last, ts);
            fullSnapshot |= event.path("type").asInt(-1) == FULL_SNAPSHOT;
        }
        ManifestRow manifest = new ManifestRow(envelope.tenantId(), envelope.siteId(), envelope.sessionId(),
                envelope.chunkSeq(),
                ReplayObjects.objectKey(envelope.tenantId(), envelope.sessionId(), envelope.chunkSeq()),
                compressed.length, events.size(), Instant.ofEpochMilli(first), Instant.ofEpochMilli(last),
                fullSnapshot, envelope.receivedAt());
        return new ReplayChunk(envelope, compressed, manifest);
    }

    @Override
    public String toString() {
        return "ReplayChunk[" + manifest.objectKey() + "]";   // no content in logs
    }
}
