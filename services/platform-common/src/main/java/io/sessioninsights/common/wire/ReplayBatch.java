package io.sessioninsights.common.wire;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Inbound {@code POST /v1/replay} body: one rrweb chunk. Exactly one of {@code payload}
 * (base64 of the SDK-compressed rrweb events) or {@code events} (raw rrweb event array)
 * is set. {@code chunkSeq} orders chunks within a session.
 */
public record ReplayBatch(
        String siteKey,
        UUID sessionId,
        Integer chunkSeq,
        String payload,
        JsonNode events) {

    @Override
    public String toString() {
        return "ReplayBatch[sessionId=" + sessionId + ", chunkSeq=" + chunkSeq + "]";
    }
}
