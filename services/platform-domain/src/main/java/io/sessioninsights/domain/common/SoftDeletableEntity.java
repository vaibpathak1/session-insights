package io.sessioninsights.domain.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Base for configuration entities (FR-TEN-2). Subclasses declare their own
 * {@code @SQLDelete} (turning delete into {@code is_active = false}) and
 * {@code @SQLRestriction("is_active")} so soft-deleted rows are invisible to queries.
 */
@MappedSuperclass
public abstract class SoftDeletableEntity {

    @Column(name = "is_active", nullable = false, insertable = false, updatable = false)
    private boolean active = true;

    @Column(name = "deleted_at", insertable = false, updatable = false)
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public boolean isActive() {
        return active;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
