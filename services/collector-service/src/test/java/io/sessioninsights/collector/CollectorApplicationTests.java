package io.sessioninsights.collector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/** The application starts against real PostgreSQL and Kafka, connected as the collector role. */
class CollectorApplicationTests extends CollectorIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void connectsAsTheLeastPrivilegeCollectorRole() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("insights_collector");
    }
}
