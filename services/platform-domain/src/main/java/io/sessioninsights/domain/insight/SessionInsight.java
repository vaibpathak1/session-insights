package io.sessioninsights.domain.insight;

import io.sessioninsights.domain.tenant.ModelProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** AI analysis of one session (F6), with a 768-dim embedding for similarity search (ADR-0005). */
@Entity
@Table(name = "session_insight")
public class SessionInsight {

    public static final int EMBEDDING_DIMENSIONS = 768;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "session_insight_seq")
    @SequenceGenerator(name = "session_insight_seq", sequenceName = "session_insight_seq", allocationSize = 50)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    private String summary;

    @Column(name = "probable_cause")
    private String probableCause;

    @Column(name = "friction_score")
    private Short frictionScore;

    @Column(name = "auto_tags", nullable = false)
    private String[] autoTags = new String[0];

    @Column(name = "human_notes")
    private String humanNotes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Evidence> evidence = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSIONS)
    private float[] embedding;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_provider")
    private ModelProvider modelProvider;

    @Column(name = "model_name")
    private String modelName;

    /** Guardrail rule that forced REVIEW_REQUIRED, if any (ADR-0005: code decides escalation). */
    @Column(name = "guardrail_rule")
    private String guardrailRule;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected SessionInsight() {
    }

    public SessionInsight(UUID tenantId, UUID sessionId, ModelProvider modelProvider, String modelName) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.modelProvider = modelProvider;
        this.modelName = modelName;
    }

    public void setAnalysis(String summary, String probableCause, Short frictionScore,
                            List<String> autoTags, List<Evidence> evidence) {
        this.summary = summary;
        this.probableCause = probableCause;
        this.frictionScore = frictionScore;
        this.autoTags = autoTags.toArray(String[]::new);
        this.evidence = new ArrayList<>(evidence);
    }

    public void setEmbedding(float[] embedding) {
        if (embedding != null && embedding.length != EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("Embedding must have " + EMBEDDING_DIMENSIONS
                    + " dimensions, got " + embedding.length);
        }
        this.embedding = embedding == null ? null : embedding.clone();
    }

    public void setGuardrailRule(String guardrailRule) {
        this.guardrailRule = guardrailRule;
    }

    public void setHumanNotes(String humanNotes) {
        this.humanNotes = humanNotes;
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

    public String getSummary() {
        return summary;
    }

    public String getProbableCause() {
        return probableCause;
    }

    public Short getFrictionScore() {
        return frictionScore;
    }

    public List<String> getAutoTags() {
        return List.of(autoTags);
    }

    public String getHumanNotes() {
        return humanNotes;
    }

    public List<Evidence> getEvidence() {
        return List.copyOf(evidence);
    }

    public float[] getEmbedding() {
        return embedding == null ? null : embedding.clone();
    }

    public ModelProvider getModelProvider() {
        return modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public String getGuardrailRule() {
        return guardrailRule;
    }

    public Long getVersion() {
        return version;
    }
}
