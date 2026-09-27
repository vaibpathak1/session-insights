package io.sessioninsights.api;

import com.clickhouse.client.api.Client;
import io.sessioninsights.db.testing.TestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the real application against PostgreSQL + ClickHouse: both migration sets must apply. */
@SpringBootTest
@Testcontainers
class ApiApplicationTests {

    @Container
    static final PostgreSQLContainer postgres = TestContainers.postgres();

    @Container
    static final GenericContainer<?> clickhouse = TestContainers.clickhouse();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("clickhouse.endpoint", () -> TestContainers.clickhouseEndpoint(clickhouse));
        registry.add("clickhouse.database", () -> TestContainers.DATABASE);
        registry.add("clickhouse.username", () -> TestContainers.USER);
        registry.add("clickhouse.password", () -> TestContainers.PASSWORD);
    }

    @Autowired
    Client clickHouseClient;

    @Test
    void contextLoadsAndClickHouseMigrationsRan() {
        assertThat(clickHouseClient.queryAll("EXISTS TABLE schema_migrations").getFirst().getInteger(1))
                .isEqualTo(1);
    }
}
