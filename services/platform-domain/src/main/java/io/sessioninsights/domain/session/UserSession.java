package io.sessioninsights.domain.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Session metadata (F3). Events live in ClickHouse and are linked only by
 * {@code tenant_id + session_id} (ADR-0002). Concurrent edits fail via {@code @Version}.
 */
@Entity
@Table(name = "user_session")
public class UserSession {

    /** Assigned: the SDK generates the session id. */
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "end_user_id")
    private UUID endUserId;

    @Column(name = "anonymous_id", nullable = false, updatable = false)
    private String anonymousId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    private String platform;

    private String browser;

    @Column(name = "entry_url")
    private String entryUrl;

    @Column(name = "friction_score")
    private Short frictionScore;

    @Column(name = "rage_click_count", nullable = false)
    private int rageClickCount;

    @Column(name = "dead_click_count", nullable = false)
    private int deadClickCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false)
    private AnalysisStatus analysisStatus = AnalysisStatus.PENDING;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected UserSession() {
    }

    public UserSession(UUID id, UUID tenantId, UUID siteId, String anonymousId, Instant startedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.siteId = siteId;
        this.anonymousId = anonymousId;
        this.startedAt = startedAt;
        this.lastActiveAt = startedAt;
    }

    public void setClientInfo(String platform, String browser, String entryUrl) {
        this.platform = platform;
        this.browser = browser;
        this.entryUrl = entryUrl;
    }

    public void touch(Instant at, int pageCount) {
        if (at.isAfter(lastActiveAt)) {
            lastActiveAt = at;
        }
        this.pageCount = pageCount;
        this.durationMs = lastActiveAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    public void end(Instant at) {
        this.endedAt = at;
        touch(at, pageCount);
    }

    public void setSignals(short frictionScore, int rageClicks, int deadClicks, int errors) {
        this.frictionScore = frictionScore;
        this.rageClickCount = rageClicks;
        this.deadClickCount = deadClicks;
        this.errorCount = errors;
    }

    public void setAnalysisStatus(AnalysisStatus analysisStatus) {
        this.analysisStatus = analysisStatus;
    }

    public void assignTo(UUID appUserId) {
        this.assignedTo = appUserId;
    }

    public void linkEndUser(UUID endUserId) {
        this.endUserId = endUserId;
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

    public UUID getEndUserId() {
        return endUserId;
    }

    public String getAnonymousId() {
        return anonymousId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getPageCount() {
        return pageCount;
    }

    public String getPlatform() {
        return platform;
    }

    public String getBrowser() {
        return browser;
    }

    public String getEntryUrl() {
        return entryUrl;
    }

    public Short getFrictionScore() {
        return frictionScore;
    }

    public int getRageClickCount() {
        return rageClickCount;
    }

    public int getDeadClickCount() {
        return deadClickCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public AnalysisStatus getAnalysisStatus() {
        return analysisStatus;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public Long getVersion() {
        return version;
    }
}
