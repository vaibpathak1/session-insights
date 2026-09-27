package io.sessioninsights.domain.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndUserRepository extends JpaRepository<EndUser, UUID> {

    Optional<EndUser> findBySiteIdAndExternalUserId(UUID siteId, String externalUserId);
}
