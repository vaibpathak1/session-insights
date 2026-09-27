package io.sessioninsights.domain.review;

import io.sessioninsights.domain.session.AnalysisStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Append-only audit record of a review action (F7). Insert-only; the DB role cannot update it. */
@Entity
@Immutable
@Table(name = "review_event")
public class ReviewEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** Null for system (AI / guardrail) transitions. */
    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private AnalysisStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private AnalysisStatus toStatus;

    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> after;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReviewEvent() {
    }

    public ReviewEvent(UUID tenantId, UUID sessionId, UUID actorId, String action,
                       AnalysisStatus fromStatus, AnalysisStatus toStatus, String note,
                       Map<String, Object> before, Map<String, Object> after) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.actorId = actorId;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.note = note;
        this.before = before;
        this.after = after;
    }

    public Long getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public AnalysisStatus getFromStatus() {
        return fromStatus;
    }

    public AnalysisStatus getToStatus() {
        return toStatus;
    }

    public String getNote() {
        return note;
    }

    public Map<String, Object> getBefore() {
        return before;
    }

    public Map<String, Object> getAfter() {
        return after;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
