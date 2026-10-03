package io.sessioninsights.api.sessions;

import io.sessioninsights.api.security.ApiPrincipal;
import io.sessioninsights.api.sessions.Dtos.EventPage;
import io.sessioninsights.api.sessions.Dtos.ReplayManifest;
import io.sessioninsights.api.sessions.Dtos.SessionDetail;
import io.sessioninsights.api.sessions.Dtos.SessionPage;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Sessions, their events and their replay (task 5.7, {@code docs/api/openapi.yaml}). Every
 * endpoint requires authentication and reads only the caller's tenant; open (live) sessions
 * work like closed ones.
 */
@RestController
@RequestMapping(value = "/api/v1/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
class SessionController {

    static final int MAX_SESSIONS = 200;
    static final int MAX_EVENTS = 1000;
    private static final Set<String> STATUSES = Set.of("open", "closed");

    private final SessionService service;

    SessionController(SessionService service) {
        this.service = service;
    }

    @GetMapping
    SessionPage listSessions(@AuthenticationPrincipal ApiPrincipal user,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                             @RequestParam(required = false) String url,
                             @RequestParam(required = false) Boolean hasError,
                             @RequestParam(required = false) Long minDurationMs,
                             @RequestParam(required = false) Long maxDurationMs,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_SESSIONS) {
            throw new InvalidParameterException("limit");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new InvalidParameterException("status");
        }
        if (minDurationMs != null && maxDurationMs != null && minDurationMs > maxDurationMs) {
            throw new InvalidParameterException("minDurationMs");
        }
        return service.list(user, new SessionQueries.Filter(from, to, url, hasError, minDurationMs, maxDurationMs, status),
                cursor, limit);
    }

    @GetMapping("/{sessionId}")
    SessionDetail getSession(@AuthenticationPrincipal ApiPrincipal user, @PathVariable UUID sessionId) {
        return service.get(user, sessionId);
    }

    @GetMapping("/{sessionId}/events")
    EventPage listEvents(@AuthenticationPrincipal ApiPrincipal user, @PathVariable UUID sessionId,
                         @RequestParam(required = false) String after,
                         @RequestParam(defaultValue = "500") int limit) {
        if (limit < 1 || limit > MAX_EVENTS) {
            throw new InvalidParameterException("limit");
        }
        return service.events(user, sessionId, after, limit);
    }

    @GetMapping("/{sessionId}/replay")
    ReplayManifest getReplayManifest(@AuthenticationPrincipal ApiPrincipal user, @PathVariable UUID sessionId) {
        return service.manifest(user, sessionId);
    }

    /** The chunk's rrweb event array as JSON, streamed decompressed (HTTP gzip on the wire). */
    @GetMapping(value = "/{sessionId}/replay/{seq}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StreamingResponseBody> getReplayChunk(@AuthenticationPrincipal ApiPrincipal user,
                                                         @PathVariable UUID sessionId, @PathVariable int seq) {
        InputStream json = service.chunk(user, sessionId, seq);
        StreamingResponseBody body = out -> {
            try (json) {
                json.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(body);
    }
}
