package io.sessioninsights.domain.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A recorded visitor of a site (F2); anonymous until {@link #identify}. Hard-deleted on erasure. */
@Entity
@Table(name = "end_user")
public class EndUser {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "external_user_id")
    private String externalUserId;

    @Column(name = "anonymous_id", nullable = false)
    private String anonymousId;

    /** Already redacted server-side before it reaches the entity. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> traits = new HashMap<>();

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected EndUser() {
    }

    public EndUser(UUID tenantId, UUID siteId, String anonymousId, Instant seenAt) {
        this.tenantId = tenantId;
        this.siteId = siteId;
        this.anonymousId = anonymousId;
        this.firstSeenAt = seenAt;
        this.lastSeenAt = seenAt;
    }

    public void identify(String externalUserId, Map<String, Object> redactedTraits) {
        this.externalUserId = externalUserId;
        this.traits = new HashMap<>(redactedTraits);
    }

    public void seen(Instant at) {
        if (at.isAfter(lastSeenAt)) {
            lastSeenAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public String getAnonymousId() {
        return anonymousId;
    }

    public Map<String, Object> getTraits() {
        return traits;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Long getVersion() {
        return version;
    }
}
