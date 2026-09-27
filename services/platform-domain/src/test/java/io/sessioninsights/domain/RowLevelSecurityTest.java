package io.sessioninsights.domain;

import io.sessioninsights.domain.session.UserSession;
import io.sessioninsights.domain.session.UserSessionRepository;
import io.sessioninsights.domain.tenancy.TenantAwareJpaTransactionManager;
import io.sessioninsights.domain.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowLevelSecurityTest extends DomainIntegrationTest {

    @Autowired
    EntityManager em;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    UserSessionRepository sessions;

    @Test
    void usesTenantAwareTransactionManager() {
        assertThat(transactionManager).isInstanceOf(TenantAwareJpaTransactionManager.class);
    }

    @Test
    void nativeQueryOnlySeesCurrentTenantsSessions() {
        Fixture a = fixture();
        Fixture b = fixture();

        List<Object> visibleToA = TenantContext.callAs(a.tenantId(), () -> tx.execute(status ->
                List.copyOf(em.createNativeQuery("SELECT id FROM user_session").getResultList())));
        assertThat(visibleToA).containsExactly(a.sessionId());

        Number bRowsSeenByA = TenantContext.callAs(a.tenantId(), () -> tx.execute(status ->
                (Number) em.createNativeQuery("SELECT count(*) FROM user_session WHERE id = ?1")
                        .setParameter(1, b.sessionId()).getSingleResult()));
        assertThat(bRowsSeenByA.longValue()).isZero();
    }

    @Test
    void repositoryOnlySeesCurrentTenantsSessions() {
        Fixture a = fixture();
        Fixture b = fixture();
        TenantContext.runAs(a.tenantId(), () -> tx.executeWithoutResult(status -> {
            assertThat(sessions.findById(b.sessionId())).isEmpty();
            assertThat(sessions.findById(a.sessionId())).isPresent();
            assertThat(sessions.findAll()).extracting(UserSession::getId).containsExactly(a.sessionId());
        }));
    }

    @Test
    void withoutTenantNothingIsVisible() {
        fixture();
        Number rows = tx.execute(status ->
                (Number) em.createNativeQuery("SELECT count(*) FROM user_session").getSingleResult());
        assertThat(rows.longValue()).isZero();
    }

    @Test
    void tenantSettingDoesNotLeakToTheNextTransaction() {
        Fixture a = fixture();
        TenantContext.runAs(a.tenantId(), () -> tx.executeWithoutResult(status -> { }));
        Object setting = tx.execute(status ->
                em.createNativeQuery("SELECT current_setting('app.tenant_id', true)").getSingleResult());
        assertThat(setting == null ? "" : setting.toString()).isNotEqualTo(a.tenantId().toString());
        assertThat(TenantContext.current()).isEmpty();
    }
}
