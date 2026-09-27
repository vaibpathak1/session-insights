package io.sessioninsights.db;

import com.clickhouse.client.api.Client;
import io.sessioninsights.db.testing.TestContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ClickHouseMigratorTest {

    @Container
    static final GenericContainer<?> clickhouse = TestContainers.clickhouse();

    static Client client;

    @BeforeAll
    static void connect() {
        client = TestContainers.clickhouseClient(clickhouse);
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void appliesOnceThenNoOpThenRejectsTamperedFile() {
        ClickHouseMigrator migrator = new ClickHouseMigrator(client, "db/migration/ch-test");

        assertThat(migrator.migrate()).isEqualTo(2);
        assertThat(client.queryAll("SELECT name FROM system.columns WHERE database = currentDatabase() AND table = 't_two'"))
                .extracting(r -> r.getString("name")).containsExactly("id", "name");

        assertThat(migrator.migrate()).isZero();
        assertThat(client.queryAll("SELECT count() AS c FROM schema_migrations").getFirst().getLong("c")).isEqualTo(2);

        ClickHouseMigrator tampered = new ClickHouseMigrator(client, "db/migration/ch-test-tampered");
        assertThatThrownBy(tampered::migrate)
                .isInstanceOf(ClickHouseMigrator.MigrationException.class)
                .hasMessageContaining("Checksum mismatch for V1__one");
    }

    @Test
    void splitsStatementsAndDropsCommentLines() {
        assertThat(ClickHouseMigrator.split("-- c\nSELECT 1;\nSELECT 'a;b';\n\n"))
                .containsExactly("SELECT 1", "SELECT 'a;b'");
    }
}
