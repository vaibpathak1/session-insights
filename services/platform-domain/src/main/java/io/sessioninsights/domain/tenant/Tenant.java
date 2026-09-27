package io.sessioninsights.domain.tenant;

import io.sessioninsights.domain.common.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

@Entity
@Table(name = "tenant")
@SQLDelete(sql = "UPDATE tenant SET is_active = false, deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("is_active")
public class Tenant extends SoftDeletableEntity {

    /** Assigned, not generated: the tenant id must be known before the row is visible under RLS. */
    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "event_retention_days", nullable = false)
    private int eventRetentionDays = 30;

    @Column(name = "replay_retention_days", nullable = false)
    private int replayRetentionDays = 30;

    @Column(name = "insight_retention_days", nullable = false)
    private int insightRetentionDays = 395;

    @Column(name = "external_llm_allowed", nullable = false)
    private boolean externalLlmAllowed;

    protected Tenant() {
    }

    public Tenant(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getEventRetentionDays() {
        return eventRetentionDays;
    }

    public int getReplayRetentionDays() {
        return replayRetentionDays;
    }

    public int getInsightRetentionDays() {
        return insightRetentionDays;
    }

    public void setRetentionDays(int events, int replays, int insights) {
        this.eventRetentionDays = events;
        this.replayRetentionDays = replays;
        this.insightRetentionDays = insights;
    }

    public boolean isExternalLlmAllowed() {
        return externalLlmAllowed;
    }

    public void setExternalLlmAllowed(boolean externalLlmAllowed) {
        this.externalLlmAllowed = externalLlmAllowed;
    }
}
