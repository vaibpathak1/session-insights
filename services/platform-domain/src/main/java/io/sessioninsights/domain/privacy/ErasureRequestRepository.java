package io.sessioninsights.domain.privacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ErasureRequestRepository extends JpaRepository<ErasureRequest, UUID> {

    List<ErasureRequest> findByStatus(ErasureStatus status);
}
