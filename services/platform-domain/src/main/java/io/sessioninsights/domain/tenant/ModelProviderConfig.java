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

/** Per-tenant model selection. Holds no secrets: provider API keys come from the environment. */
@Entity
@Table(name = "model_provider_config")
@SQLDelete(sql = "UPDATE model_provider_config SET is_active = false, deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("is_active")
public class ModelProviderConfig extends SoftDeletableEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ModelProvider provider;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "chat_model", nullable = false)
    private String chatModel;

    @Column(name = "embedding_model")
    private String embeddingModel;

    protected ModelProviderConfig() {
    }

    public ModelProviderConfig(UUID tenantId, ModelProvider provider, String chatModel, String embeddingModel) {
        this.tenantId = tenantId;
        this.provider = provider;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public ModelProvider getProvider() {
        return provider;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getChatModel() {
        return chatModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setModels(String chatModel, String embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }
}
