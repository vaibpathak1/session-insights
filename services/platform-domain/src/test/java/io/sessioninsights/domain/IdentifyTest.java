package io.sessioninsights.domain;

import io.sessioninsights.domain.session.EndUser;
import io.sessioninsights.domain.session.EndUserRepository;
import io.sessioninsights.domain.session.UserSession;
import io.sessioninsights.domain.session.UserSessionRepository;
import io.sessioninsights.domain.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifyTest extends DomainIntegrationTest {

    @Autowired EndUserRepository endUsers;
    @Autowired UserSessionRepository sessions;

    @Test
    void sessionsSharingAnAnonymousIdAreLinkedToOneEndUserAfterIdentify() {
        Fixture f = fixture();   // its session uses anonymous id "anon-1"
        UUID secondSession = UUID.randomUUID();
        UUID otherVisitorSession = UUID.randomUUID();
        Instant now = Instant.now();

        UUID endUserId = TenantContext.callAs(f.tenantId(), () -> {
            tx.executeWithoutResult(s -> {
                sessions.save(new UserSession(secondSession, f.tenantId(), f.siteId(), "anon-1", now));
                sessions.save(new UserSession(otherVisitorSession, f.tenantId(), f.siteId(), "anon-2", now));
            });
            return tx.execute(s -> {
                EndUser user = new EndUser(f.tenantId(), f.siteId(), "anon-1", now);
                user.identify("customer-42", Map.of("plan", "pro"));
                UUID id = endUsers.save(user).getId();
                assertThat(sessions.linkToEndUser(f.siteId(), "anon-1", id)).isEqualTo(2);
                assertThat(sessions.linkToEndUser(f.siteId(), "anon-1", id)).as("idempotent").isZero();
                return id;
            });
        });

        TenantContext.runAs(f.tenantId(), () -> tx.executeWithoutResult(s -> {
            assertThat(sessions.findBySiteIdAndAnonymousId(f.siteId(), "anon-1"))
                    .hasSize(2)
                    .allSatisfy(session -> {
                        assertThat(session.getEndUserId()).isEqualTo(endUserId);
                        assertThat(session.getVersion()).isEqualTo(1L);
                    });
            assertThat(sessions.findById(otherVisitorSession).orElseThrow().getEndUserId()).isNull();
            assertThat(endUsers.findBySiteIdAndExternalUserId(f.siteId(), "customer-42"))
                    .get().extracting(EndUser::getId).isEqualTo(endUserId);
        }));
    }
}
