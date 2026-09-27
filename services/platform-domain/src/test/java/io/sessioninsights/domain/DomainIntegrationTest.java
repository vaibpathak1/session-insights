package io.sessioninsights.domain;

import io.sessioninsights.db.testing.TestContainers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

/**
 * Real PostgreSQL (pgvector image), migrations applied by Flyway as the owner, the
 * application connected as the non-owner app role so RLS applies. One container per JVM,
 * shared by all subclasses (and their cached Spring context).
 */
@SpringBootTest(classes = DomainTestApplication.class)
public abstract class DomainIntegrationTest {

    static final PostgreSQLContainer POSTGRES = TestContainers.postgres();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&reWriteBatchedInserts=true");
        registry.add("spring.datasource.username", () -> TestContainers.APP_USER);
        registry.add("spring.datasource.password", () -> TestContainers.APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/postgres");
        registry.add("spring.flyway.placeholders.appUser", () -> TestContainers.APP_USER);
        registry.add("spring.flyway.placeholders.appPassword", () -> TestContainers.APP_PASSWORD);
    }

    @Autowired
    protected TransactionTemplate tx;

    /** Superuser connection: bypasses RLS, for arranging fixtures and inspecting raw rows. */
    protected static JdbcClient owner() {
        var ds = new SingleConnectionDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        return JdbcClient.create(ds);
    }

    /** Inserts tenant + site + session as owner and returns the ids. */
    protected static Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        JdbcClient db = owner();
        db.sql("INSERT INTO tenant (id, name) VALUES (?, 'test')").params(tenant).update();
        db.sql("INSERT INTO site (id, tenant_id, name) VALUES (?, ?, 'site')").params(site, tenant).update();
        db.sql("INSERT INTO user_session (id, tenant_id, site_id, anonymous_id, started_at, last_active_at)"
                + " VALUES (?, ?, ?, 'anon-1', now(), now())").params(session, tenant, site).update();
        return new Fixture(tenant, site, session);
    }

    protected record Fixture(UUID tenantId, UUID siteId, UUID sessionId) {
    }
}
