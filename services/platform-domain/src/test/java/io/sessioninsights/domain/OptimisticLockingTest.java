package io.sessioninsights.domain;

import io.sessioninsights.domain.session.AnalysisStatus;
import io.sessioninsights.domain.session.UserSession;
import io.sessioninsights.domain.session.UserSessionRepository;
import io.sessioninsights.domain.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OptimisticLockingTest extends DomainIntegrationTest {

    @Autowired UserSessionRepository sessions;

    @Test
    void concurrentUpdatesOfOneSessionOneWinsOneFails() throws Exception {
        Fixture f = fixture();
        CyclicBarrier bothRead = new CyclicBarrier(2);

        List<Throwable> failures = new ArrayList<>();
        int succeeded = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> results = new ArrayList<>();
            for (AnalysisStatus target : List.of(AnalysisStatus.HUMAN_APPROVED, AnalysisStatus.HUMAN_REJECTED)) {
                results.add(pool.submit(() -> TenantContext.runAs(f.tenantId(), () -> tx.executeWithoutResult(s -> {
                    UserSession session = sessions.findById(f.sessionId()).orElseThrow();
                    await(bothRead);   // both transactions hold version 0 before either writes
                    session.setAnalysisStatus(target);
                }))));
            }
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(failures).singleElement().isInstanceOf(ObjectOptimisticLockingFailureException.class);
        UserSession after = TenantContext.callAs(f.tenantId(), () -> tx.execute(s ->
                sessions.findById(f.sessionId()).orElseThrow()));
        assertThat(after.getVersion()).isEqualTo(1L);
        assertThat(after.getAnalysisStatus()).isIn(AnalysisStatus.HUMAN_APPROVED, AnalysisStatus.HUMAN_REJECTED);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
