package io.sessioninsights.db;

import com.zaxxer.hikari.HikariDataSource;
import io.sessioninsights.db.testing.TestContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PoolWarmUpTest {

    @Test
    void createsThePoolOnAPlatformThreadBeforeAnyRequest() {
        try (PostgreSQLContainer postgres = TestContainers.postgres()) {
            postgres.start();
            try (HikariDataSource ds = dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                assertThat(ds.isRunning()).isFalse();   // Boot's lazy HikariDataSource
                assertThat(PoolWarmUp.warmUp(ds, Duration.ofSeconds(5))).isTrue();
                assertThat(ds.isRunning()).isTrue();
            }
        }
    }

    @Test
    void isNonFatalWhenTheDatabaseIsDown(CapturedOutput output) throws Exception {
        String url = "jdbc:postgresql://localhost:" + freePort() + "/insights";
        try (HikariDataSource ds = dataSource(url, "insights", "secret-password-value")) {
            long start = System.nanoTime();
            assertThat(PoolWarmUp.warmUp(ds, Duration.ofSeconds(2))).isTrue();   // pool exists, no connection
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            // the platform thread logs once the connection attempt times out
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!output.getAll().contains("Database unreachable at startup") && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(output.getAll()).contains("Database unreachable at startup").doesNotContain("secret-password-value");
        }
    }

    private static HikariDataSource dataSource(String url, String user, String password) {
        HikariDataSource ds = new HikariDataSource();   // no-arg: lazy, as Boot builds it
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setInitializationFailTimeout(-1);
        ds.setConnectionTimeout(1000);
        return ds;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
