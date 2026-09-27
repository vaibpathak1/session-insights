package io.sessioninsights.domain.insight;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SessionInsightRepository extends JpaRepository<SessionInsight, Long> {

    List<SessionInsight> findBySessionId(UUID sessionId);

    /** Nearest insights by cosine distance; served by the HNSW index. */
    @Query(value = """
            SELECT * FROM session_insight
             WHERE tenant_id = :tenantId AND embedding IS NOT NULL
             ORDER BY embedding <=> CAST(:query AS vector)
             LIMIT :limit""", nativeQuery = true)
    List<SessionInsight> findNearest(@Param("tenantId") UUID tenantId, @Param("query") String vectorLiteral,
                                     @Param("limit") int limit);

    default List<SessionInsight> findNearest(UUID tenantId, float[] query, int limit) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < query.length; i++) {
            literal.append(i == 0 ? "" : ",").append(query[i]);
        }
        return findNearest(tenantId, literal.append(']').toString(), limit);
    }
}
