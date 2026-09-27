package io.sessioninsights.api;

import io.sessioninsights.db.testing.TestContainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL + ClickHouse started once per JVM and shared by all api-service integration tests. */
public abstract class ApiIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = TestContainers.postgres();
    protected static final GenericContainer<?> CLICKHOUSE = TestContainers.clickhouse();

    static {
        POSTGRES.start();
        CLICKHOUSE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&reWriteBatchedInserts=true");
        registry.add("spring.datasource.username", () -> TestContainers.APP_USER);
        registry.add("spring.datasource.password", () -> TestContainers.APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        TestContainers.flywayPlaceholders().forEach((name, value) ->
                registry.add("spring.flyway.placeholders." + name, () -> value));
        registry.add("clickhouse.endpoint", () -> TestContainers.clickhouseEndpoint(CLICKHOUSE));
        registry.add("clickhouse.database", () -> TestContainers.DATABASE);
        registry.add("clickhouse.username", () -> TestContainers.USER);
        registry.add("clickhouse.password", () -> TestContainers.PASSWORD);
    }
}
