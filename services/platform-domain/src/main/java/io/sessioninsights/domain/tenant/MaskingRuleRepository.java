package io.sessioninsights.domain.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MaskingRuleRepository extends JpaRepository<MaskingRule, UUID> {
}
