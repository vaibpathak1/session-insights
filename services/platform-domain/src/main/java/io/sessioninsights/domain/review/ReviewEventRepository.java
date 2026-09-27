package io.sessioninsights.domain.review;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Append-only: exposes save and reads, no delete. */
public interface ReviewEventRepository extends Repository<ReviewEvent, Long> {

    ReviewEvent save(ReviewEvent event);

    List<ReviewEvent> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
