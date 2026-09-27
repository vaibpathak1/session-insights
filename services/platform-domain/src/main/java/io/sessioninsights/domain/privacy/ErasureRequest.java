package io.sessioninsights.domain.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Right-to-erasure request (F8, FR-PRV-1); progress is tracked per store. */
@Entity
@Table(name = "erasure_request")
public class ErasureRequest {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false)
    private ErasureSubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ErasureStatus status = ErasureStatus.REQUESTED;

    /** e.g. {"postgres": "DONE", "clickhouse": "PENDING", "s3": "PENDING"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> progress = new HashMap<>();

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ErasureRequest() {
    }

    public ErasureRequest(UUID tenantId, ErasureSubjectType subjectType, UUID subjectId, UUID requestedBy) {
        this.tenantId = tenantId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.requestedBy = requestedBy;
    }

    public void recordProgress(String store, String state) {
        progress.put(store, state);
    }

    public void setStatus(ErasureStatus status, Instant at) {
        this.status = status;
        if (status == ErasureStatus.COMPLETED) {
            this.completedAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public ErasureSubjectType getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public ErasureStatus getStatus() {
        return status;
    }

    public Map<String, String> getProgress() {
        return Map.copyOf(progress);
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getVersion() {
        return version;
    }
}
