package io.sessioninsights.domain;

import io.sessioninsights.domain.insight.SessionInsight;
import io.sessioninsights.domain.insight.SessionInsightRepository;
import io.sessioninsights.domain.tenancy.TenantContext;
import io.sessioninsights.domain.tenant.ModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VectorSearchTest extends DomainIntegrationTest {

    @Autowired SessionInsightRepository insights;

    @Test
    void nearestNeighbourReturnsTheClosestEmbedding() {
        Fixture f = fixture();
        List<Long> ids = TenantContext.callAs(f.tenantId(), () -> tx.execute(s -> List.of(
                save(f, "checkout", axis(0)),
                save(f, "search", axis(1)),
                save(f, "login", axis(2)))));

        float[] query = axis(1);
        query[2] = 0.3f;   // close to "search", somewhat towards "login", orthogonal to "checkout"

        List<SessionInsight> nearest = TenantContext.callAs(f.tenantId(), () -> tx.execute(s ->
                insights.findNearest(f.tenantId(), query, 3)));
        assertThat(nearest).extracting(SessionInsight::getSummary).containsExactly("search", "login", "checkout");
        assertThat(nearest.getFirst().getId()).isEqualTo(ids.get(1));

        // allocationSize = 50: one sequence call serves the whole batch
        assertThat(ids.get(2) - ids.get(0)).isEqualTo(2);
    }

    private Long save(Fixture f, String summary, float[] embedding) {
        SessionInsight insight = new SessionInsight(f.tenantId(), f.sessionId(), ModelProvider.OLLAMA, "qwen2.5:7b");
        insight.setAnalysis(summary, null, null, List.of(), List.of());
        insight.setEmbedding(embedding);
        return insights.save(insight).getId();
    }

    private static float[] axis(int dimension) {
        float[] v = new float[SessionInsight.EMBEDDING_DIMENSIONS];
        v[dimension] = 1f;
        return v;
    }
}
