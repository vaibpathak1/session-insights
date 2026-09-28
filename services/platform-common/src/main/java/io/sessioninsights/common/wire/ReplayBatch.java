package io.sessioninsights.common.wire;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Inbound {@code POST /v1/replay} body: one rrweb chunk, {@code events} being the raw rrweb
 * event array (compressed at the HTTP layer with gzip). {@code chunkSeq} orders chunks within
 * a session. The former base64 {@code payload} form is gone (Phase 4b); like any unknown
 * field it is ignored, so a body with only {@code payload} fails validation.
 */
public record ReplayBatch(
        String siteKey,
        UUID sessionId,
        Integer chunkSeq,
        JsonNode events) {

    @Override
    public String toString() {
        return "ReplayBatch[sessionId=" + sessionId + ", chunkSeq=" + chunkSeq + "]";
    }
}
