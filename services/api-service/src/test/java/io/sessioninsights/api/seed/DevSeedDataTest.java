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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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
        // Every api-service test runs the dev profile, so whichever context started first has
        // already seeded (and printed the key in another class's output). Start from scratch so
        // creation is observed here, whatever the test-class order.
        removeDevSeed();
        int before = occurrences(output.getAll());

        assertThat(seed.seed()).as("created").isTrue();
        assertThat(occurrences(output.getAll()) - before).as("key printed once, on creation").isEqualTo(1);
        assertThat(seed.seed()).as("already present").isFalse();
        assertThat(occurrences(output.getAll()) - before).as("never printed again").isEqualTo(1);

        TenantContext.runAs(DevSeedData.DEV_TENANT_ID, () -> tx.executeWithoutResult(s -> {
            assertThat(tenants.findAll()).hasSize(1);
            assertThat(sites.findAll()).singleElement()
                    .satisfies(site -> assertThat(site.getAllowedOrigins()).containsExactly(DevSeedData.DEV_ORIGIN));
            assertThat(siteKeys.findAll()).singleElement()
                    .satisfies(k -> assertThat(k.getKeyPrefix()).startsWith("sk_dev_"));
            assertThat(appUsers.findAll()).extracting(AppUser::getRole).containsExactly(AppUserRole.ADMIN);
        }));
    }

    private static int occurrences(String text) {
        return text.split("sk_dev_", -1).length - 1;
    }

    /** As the owner (bypasses RLS): the dev tenant's rows, children first. */
    private static void removeDevSeed() {
        JdbcTemplate owner = new JdbcTemplate(new SingleConnectionDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), true));
        for (String table : new String[] {"site_key", "app_user", "site"}) {
            owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", DevSeedData.DEV_TENANT_ID);
        }
        owner.update("DELETE FROM tenant WHERE id = ?", DevSeedData.DEV_TENANT_ID);
    }
}
