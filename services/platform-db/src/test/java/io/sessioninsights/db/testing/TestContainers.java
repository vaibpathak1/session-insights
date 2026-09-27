package io.sessioninsights.db.testing;

import com.clickhouse.client.api.Client;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

/** Container factories shared by platform-db, platform-domain, api-service and collector-service tests. */
public final class TestContainers {

    public static final String DATABASE = "insights";
    public static final String USER = "insights";
    public static final String PASSWORD = "insights_test_pw";
    public static final String APP_USER = "insights_app";
    public static final String APP_PASSWORD = "insights_app_test_pw";
    public static final String COLLECTOR_USER = "insights_collector";
    public static final String COLLECTOR_PASSWORD = "insights_collector_test_pw";
    public static final int CLICKHOUSE_HTTP_PORT = 8123;

    private TestContainers() {
    }

    /** Flyway placeholders for the PostgreSQL migrations (roles created by V3 and V4). */
    public static Map<String, String> flywayPlaceholders() {
        return Map.of("appUser", APP_USER, "appPassword", APP_PASSWORD,
                "collectorUser", COLLECTOR_USER, "collectorPassword", COLLECTOR_PASSWORD);
    }

    public static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(TestImages.POSTGRES)
                .withDatabaseName(DATABASE)
                .withUsername(USER)
                .withPassword(PASSWORD);
    }

    /**
     * Plain container instead of Testcontainers' ClickHouseContainer, whose readiness check
     * requires a ClickHouse JDBC driver we don't otherwise need.
     */
    public static GenericContainer<?> clickhouse() {
        return new GenericContainer<>(TestImages.CLICKHOUSE)
                .withEnv("CLICKHOUSE_DB", DATABASE)
                .withEnv("CLICKHOUSE_USER", USER)
                .withEnv("CLICKHOUSE_PASSWORD", PASSWORD)
                .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                .withExposedPorts(CLICKHOUSE_HTTP_PORT)
                .waitingFor(Wait.forHttp("/ping").forPort(CLICKHOUSE_HTTP_PORT));
    }

    public static String clickhouseEndpoint(GenericContainer<?> clickhouse) {
        return "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(CLICKHOUSE_HTTP_PORT);
    }

    public static Client clickhouseClient(GenericContainer<?> clickhouse) {
        return new Client.Builder()
                .addEndpoint(clickhouseEndpoint(clickhouse))
                .setUsername(USER)
                .setPassword(PASSWORD)
                .setDefaultDatabase(DATABASE)
                .build();
    }
}
