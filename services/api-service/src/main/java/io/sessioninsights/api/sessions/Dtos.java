package io.sessioninsights.api.sessions;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies of {@code /api/v1/sessions} (documented in docs/api/openapi.yaml). */
final class Dtos {

    private Dtos() {
    }

    /** Who the session belongs to: the identified user's external id, else the anonymous id. */
    record UserRef(UUID id, String externalId, String anonymousId) {
    }

    /**
     * One row of the session list. Counters and duration are computed when the session closes
     * (Phase 5a), so they are 0 while {@code status} is {@code open}; {@code frictionScore} is
     * null until signals exist (Phase 8).
     */
    record SessionSummary(UUID id, UserRef user, String status, Instant startedAt, Instant lastActiveAt,
                          Instant endedAt, long durationMs, int pageCount, int errorCount, String entryUrl,
                          String platform, String browser, Integer frictionScore, String analysisStatus) {
    }

    record SessionPage(List<SessionSummary> items, String nextCursor) {
    }

    /** A session with its visitor's traits (redacted server-side; empty until identify()). */
    record SessionDetail(SessionSummary session, UUID siteId, JsonNode userTraits) {
    }

    record EventItem(UUID id, String type, Instant ts, String url, String path, String title, String targetSelector,
                     String targetText, String errorMessage, String errorStack, String eventName, JsonNode props) {
    }

    record EventPage(List<EventItem> items, String nextCursor) {
    }

    record ChunkItem(int seq, Instant firstTs, Instant lastTs, int eventCount, boolean hasFullSnapshot,
                     long compressedBytes) {
    }

    record ReplayManifest(UUID sessionId, String status, List<ChunkItem> chunks) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApiError(String error, String parameter) {
    }
}
