package io.sessioninsights.domain.tenant;

import io.sessioninsights.domain.common.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "masking_rule")
@SQLDelete(sql = "UPDATE masking_rule SET is_active = false, deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("is_active")
public class MaskingRule extends SoftDeletableEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "css_selector", nullable = false)
    private String cssSelector;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaskingAction action;

    protected MaskingRule() {
    }

    public MaskingRule(UUID tenantId, UUID siteId, String cssSelector, MaskingAction action) {
        this.tenantId = tenantId;
        this.siteId = siteId;
        this.cssSelector = cssSelector;
        this.action = action;
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

    public String getCssSelector() {
        return cssSelector;
    }

    public MaskingAction getAction() {
        return action;
    }

    public void update(String cssSelector, MaskingAction action) {
        this.cssSelector = cssSelector;
        this.action = action;
    }
}
