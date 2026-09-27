package io.sessioninsights.api.seed;

import io.sessioninsights.domain.tenancy.TenantContext;
import io.sessioninsights.domain.tenant.AppUser;
import io.sessioninsights.domain.tenant.AppUserRepository;
import io.sessioninsights.domain.tenant.AppUserRole;
import io.sessioninsights.domain.tenant.Site;
import io.sessioninsights.domain.tenant.SiteKey;
import io.sessioninsights.domain.tenant.SiteKeyRepository;
import io.sessioninsights.domain.tenant.SiteKeys;
import io.sessioninsights.domain.tenant.SiteRepository;
import io.sessioninsights.domain.tenant.Tenant;
import io.sessioninsights.domain.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Local demo data for milestone M1 ({@code dev} profile only): one tenant, one site, one
 * site key and one ADMIN user. Idempotent; the site key is printed once, when created.
 */
@Component
@Profile("dev")
public class DevSeedData implements ApplicationRunner {

    public static final UUID DEV_TENANT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    static final String DEV_ORIGIN = "http://localhost:*";
    static final String DEV_ADMIN_EMAIL = "admin@example.com";

    private static final Logger log = LoggerFactory.getLogger(DevSeedData.class);

    private final TransactionTemplate tx;
    private final TenantRepository tenants;
    private final SiteRepository sites;
    private final SiteKeyRepository siteKeys;
    private final AppUserRepository appUsers;

    public DevSeedData(TransactionTemplate tx, TenantRepository tenants, SiteRepository sites,
                       SiteKeyRepository siteKeys, AppUserRepository appUsers) {
        this.tx = tx;
        this.tenants = tenants;
        this.sites = sites;
        this.siteKeys = siteKeys;
        this.appUsers = appUsers;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Returns true if the data was created by this call. */
    public boolean seed() {
        SiteKeys.IssuedKey key = TenantContext.callAs(DEV_TENANT_ID, () -> tx.execute(status -> {
            if (tenants.existsById(DEV_TENANT_ID)) {
                return null;
            }
            tenants.save(new Tenant(DEV_TENANT_ID, "Dev tenant"));
            Site site = sites.save(new Site(DEV_TENANT_ID, "Local dev site", DEV_ORIGIN));
            SiteKeys.IssuedKey issued = SiteKeys.issue("dev");
            siteKeys.save(new SiteKey(DEV_TENANT_ID, site.getId(), issued.prefix(), issued.hash()));
            appUsers.save(new AppUser(DEV_TENANT_ID, DEV_ADMIN_EMAIL, "Dev Admin", AppUserRole.ADMIN));
            return issued;
        }));
        if (key == null) {
            log.info("Dev seed data already present (tenant {})", DEV_TENANT_ID);
            return false;
        }
        // Printed exactly once, after commit; only the hash is stored.
        log.warn("Dev seed created tenant {} / admin {}. Site key (shown once, not stored): {}",
                DEV_TENANT_ID, DEV_ADMIN_EMAIL, key.plaintext());
        return true;
    }
}
