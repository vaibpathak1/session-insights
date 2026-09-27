package io.sessioninsights.api.seed;

import io.sessioninsights.api.ApiIntegrationTest;
import io.sessioninsights.domain.tenancy.TenantContext;
import io.sessioninsights.domain.tenant.AppUser;
import io.sessioninsights.domain.tenant.AppUserRepository;
import io.sessioninsights.domain.tenant.AppUserRole;
import io.sessioninsights.domain.tenant.SiteKeyRepository;
import io.sessioninsights.domain.tenant.SiteRepository;
import io.sessioninsights.domain.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
@ExtendWith(OutputCaptureExtension.class)
class DevSeedDataTest extends ApiIntegrationTest {

    @Autowired DevSeedData seed;
    @Autowired TransactionTemplate tx;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired SiteKeyRepository siteKeys;
    @Autowired AppUserRepository appUsers;

    @Test
    void seedsOnceAndNeverReprintsTheKey(CapturedOutput output) {
        // the ApplicationRunner already seeded during startup (captured output includes startup)
        assertThat(seed.seed()).isFalse();
        assertThat(output.getAll().split("sk_dev_", -1)).as("key printed exactly once").hasSize(2);

        TenantContext.runAs(DevSeedData.DEV_TENANT_ID, () -> tx.executeWithoutResult(s -> {
            assertThat(tenants.findAll()).hasSize(1);
            assertThat(sites.findAll()).singleElement()
                    .satisfies(site -> assertThat(site.getAllowedOrigins()).containsExactly(DevSeedData.DEV_ORIGIN));
            assertThat(siteKeys.findAll()).singleElement()
                    .satisfies(k -> assertThat(k.getKeyPrefix()).startsWith("sk_dev_"));
            assertThat(appUsers.findAll()).extracting(AppUser::getRole).containsExactly(AppUserRole.ADMIN);
        }));
    }
}
