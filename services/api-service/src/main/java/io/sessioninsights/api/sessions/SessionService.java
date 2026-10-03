package io.sessioninsights.api.sessions;

import io.sessioninsights.api.security.ApiPrincipal;
import io.sessioninsights.api.sessions.Dtos.ChunkItem;
import io.sessioninsights.api.sessions.Dtos.EventItem;
import io.sessioninsights.api.sessions.Dtos.EventPage;
import io.sessioninsights.api.sessions.Dtos.ReplayManifest;
import io.sessioninsights.api.sessions.Dtos.SessionDetail;
import io.sessioninsights.api.sessions.Dtos.SessionPage;
import io.sessioninsights.common.privacy.Redactor;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.events.EventCursor;
import io.sessioninsights.events.EventReader;
import io.sessioninsights.events.EventRow;
import io.sessioninsights.events.ManifestReader;
import io.sessioninsights.events.ReplayObjectReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Session reads for one authenticated user (tasks 5.6/5.7). The tenant always comes from the
 * principal: PostgreSQL through row-level security (the request runs as that tenant), ClickHouse
 * and object storage through an explicit {@code tenantId}. Sub-resources first confirm the
 * session exists for that tenant, so another tenant's session is a 404, never an empty list.
 */
@Service
public class SessionService {

    private final SessionQueries sessions;
    private final EventReader events;
    private final ManifestReader manifest;
    private final ReplayObjectReader objects;

    SessionService(SessionQueries sessions, EventReader events, ManifestReader manifest, ReplayObjectReader objects) {
        this.sessions = sessions;
        this.events = events;
        this.manifest = manifest;
        this.objects = objects;
    }

    @Transactional(readOnly = true)
    public SessionPage list(ApiPrincipal user, SessionQueries.Filter filter, String cursor, int limit) {
        SessionCursor after = cursor == null || cursor.isBlank() ? null : SessionCursor.decode(cursor);
        List<SessionQueries.Row> rows = sessions.list(user.tenantId(), filter, after, limit);
        boolean more = rows.size() > limit;
        List<SessionQueries.Row> page = more ? rows.subList(0, limit) : rows;
        String next = more ? new SessionCursor(page.getLast().summary().startedAt(), page.getLast().summary().id()).encode()
                : null;
        return new SessionPage(page.stream().map(SessionQueries.Row::summary).toList(), next);
    }

    @Transactional(readOnly = true)
    public SessionDetail get(ApiPrincipal user, UUID sessionId) {
        SessionQueries.Row row = require(user, sessionId);
        return new SessionDetail(row.summary(), row.siteId(), redact(row.traits()));
    }

    @Transactional(readOnly = true)
    public EventPage events(ApiPrincipal user, UUID sessionId, String after, int limit) {
        require(user, sessionId);
        EventCursor cursor;
        try {
            cursor = after == null || after.isBlank() ? null : EventCursor.decode(after);
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("after");
        }
        List<EventRow> rows = events.page(user.tenantId(), sessionId, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<EventRow> page = more ? rows.subList(0, limit) : rows;
        return new EventPage(page.stream().map(SessionService::item).toList(),
                more ? EventCursor.after(page.getLast()).encode() : null);
    }

    @Transactional(readOnly = true)
    public ReplayManifest manifest(ApiPrincipal user, UUID sessionId) {
        SessionQueries.Row row = require(user, sessionId);
        List<ChunkItem> chunks = manifest.findChunks(user.tenantId(), sessionId).stream()
                .map(m -> new ChunkItem(m.chunkSeq(), m.firstTs(), m.lastTs(), m.eventCount(), m.hasFullSnapshot(),
                        m.compressedBytes()))
                .toList();
        return new ReplayManifest(sessionId, row.summary().status(), chunks);
    }

    /**
     * The chunk's rrweb JSON, decompressed as it is read. The manifest row must belong to this
     * tenant's session before the object is opened; the object key is built from the tenant.
     */
    @Transactional(readOnly = true)
    public InputStream chunk(ApiPrincipal user, UUID sessionId, int seq) {
        require(user, sessionId);
        if (seq < 0 || manifest.findChunk(user.tenantId(), sessionId, seq).isEmpty()) {
            throw new SessionNotFoundException();
        }
        return objects.openDecompressed(user.tenantId(), sessionId, seq).orElseThrow(SessionNotFoundException::new);
    }

    private SessionQueries.Row require(ApiPrincipal user, UUID sessionId) {
        return sessions.find(user.tenantId(), sessionId).orElseThrow(SessionNotFoundException::new);
    }

    private static EventItem item(EventRow r) {
        return new EventItem(r.eventId(), r.eventType(), r.ts(), r.url(), r.path(), r.pageTitle(), r.targetSelector(),
                r.targetText(), r.errorMessage(), r.errorStack(), r.eventName(), r.props());
    }

    /** Traits are redacted before they leave the API (ADR-0006: defence in depth). */
    static JsonNode redact(JsonNode node) {
        if (node == null) {
            return WireJson.mapper().createObjectNode();
        }
        if (node.isString()) {
            return WireJson.mapper().getNodeFactory().stringNode(Redactor.redact(node.asString()));
        }
        if (node.isObject()) {
            ObjectNode out = WireJson.mapper().createObjectNode();
            node.properties().forEach(e -> out.set(e.getKey(), redact(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = WireJson.mapper().createArrayNode();
            node.forEach(v -> out.add(redact(v)));
            return out;
        }
        return node;
    }
}
