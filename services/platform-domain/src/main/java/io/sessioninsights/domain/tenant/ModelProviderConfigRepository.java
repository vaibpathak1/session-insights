package io.sessioninsights.domain.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ModelProviderConfigRepository extends JpaRepository<ModelProviderConfig, UUID> {
}
