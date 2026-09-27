package io.sessioninsights.domain.tenant;

import io.sessioninsights.domain.common.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "site")
@SQLDelete(sql = "UPDATE site SET is_active = false, deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("is_active")
public class Site extends SoftDeletableEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "allowed_origins", nullable = false)
    private String[] allowedOrigins = new String[0];

    @Column(name = "sampling_rate", nullable = false, precision = 4, scale = 3)
    private BigDecimal samplingRate = BigDecimal.ONE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signal_thresholds", nullable = false)
    private Map<String, Object> signalThresholds = new HashMap<>();

    protected Site() {
    }

    public Site(UUID tenantId, String name, String... allowedOrigins) {
        this.tenantId = tenantId;
        this.name = name;
        this.allowedOrigins = allowedOrigins;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String[] getAllowedOrigins() {
        return allowedOrigins.clone();
    }

    public void setAllowedOrigins(String... allowedOrigins) {
        this.allowedOrigins = allowedOrigins.clone();
    }

    public BigDecimal getSamplingRate() {
        return samplingRate;
    }

    public void setSamplingRate(BigDecimal samplingRate) {
        this.samplingRate = samplingRate;
    }

    public Map<String, Object> getSignalThresholds() {
        return signalThresholds;
    }

    public void setSignalThresholds(Map<String, Object> signalThresholds) {
        this.signalThresholds = signalThresholds;
    }
}
