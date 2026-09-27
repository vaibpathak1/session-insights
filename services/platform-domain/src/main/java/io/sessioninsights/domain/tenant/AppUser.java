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

/** A dashboard user (reviewer / admin), not a recorded end user. */
@Entity
@Table(name = "app_user")
@SQLDelete(sql = "UPDATE app_user SET is_active = false, deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("is_active")
public class AppUser extends SoftDeletableEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppUserRole role;

    @Column(name = "external_subject_id")
    private String externalSubjectId;

    protected AppUser() {
    }

    public AppUser(UUID tenantId, String email, String displayName, AppUserRole role) {
        this.tenantId = tenantId;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public AppUserRole getRole() {
        return role;
    }

    public void setRole(AppUserRole role) {
        this.role = role;
    }

    public String getExternalSubjectId() {
        return externalSubjectId;
    }

    public void setExternalSubjectId(String externalSubjectId) {
        this.externalSubjectId = externalSubjectId;
    }
}
