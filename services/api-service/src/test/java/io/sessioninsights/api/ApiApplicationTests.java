package io.sessioninsights.api;

import com.clickhouse.client.api.Client;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the real application against PostgreSQL + ClickHouse: both migration sets must apply. */
@SpringBootTest
class ApiApplicationTests extends ApiIntegrationTest {

    @Autowired
    Client clickHouseClient;

    @Autowired
    Flyway flyway;

    @Test
    void bothMigrationSetsAppliedOnStartup() {
        assertThat(flyway.info().applied()).extracting(m -> m.getVersion().getVersion())
                .containsExactly("1", "2", "3");
        assertThat(clickHouseClient.queryAll("SELECT version FROM schema_migrations ORDER BY version"))
                .extracting(r -> r.getLong("version")).containsExactly(1L);
        assertThat(clickHouseClient.queryAll("EXISTS TABLE events").getFirst().getInteger(1)).isEqualTo(1);
    }
}
