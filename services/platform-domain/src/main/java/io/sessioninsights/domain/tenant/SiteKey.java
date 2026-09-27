package io.sessioninsights.domain.tenant;

import io.sessioninsights.domain.common.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** A site's ingestion key. Only a hash is stored; see {@link SiteKeys} for issuing one. */
@Entity
@Table(name = "site_key")
@SQLDelete(sql = "UPDATE site_key SET is_active = false, deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("is_active")
public class SiteKey extends SoftDeletableEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "key_prefix", nullable = false, updatable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, updatable = false)
    private String keyHash;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected SiteKey() {
    }

    public SiteKey(UUID tenantId, UUID siteId, String keyPrefix, String keyHash) {
        this.tenantId = tenantId;
        this.siteId = siteId;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
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

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void revoke(Instant at) {
        this.revokedAt = at;
    }
}
