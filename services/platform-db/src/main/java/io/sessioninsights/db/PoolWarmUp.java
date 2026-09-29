package io.sessioninsights.db;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Creates a HikariCP pool at startup, on a platform thread (Phase 5, task 5.0).
 * <p>
 * Boot builds {@link HikariDataSource} with its no-arg constructor, so the pool is created
 * lazily inside {@code synchronized} on the first {@code getConnection()}, which also opens
 * the first connection. On Java 21 that pins the virtual thread serving the first request.
 * Once the pool exists, {@code getConnection()} takes a lock-free fast path.
 * <p>
 * With {@code initialization-fail-timeout: -1} creating the pool never fails and never waits
 * for PostgreSQL, so this is non-fatal: if the database is down, one warning is logged, the
 * pool keeps trying in the background and requests see the usual connection timeouts.
 */
public final class PoolWarmUp {

    private static final Logger log = LoggerFactory.getLogger(PoolWarmUp.class);

    private PoolWarmUp() {
    }

    /**
     * Starts the pool on a platform thread and waits up to {@code maxWait} for it to exist
     * (not for a connection). Returns true if the pool is running.
     */
    public static boolean warmUp(DataSource dataSource, Duration maxWait) {
        HikariDataSource hikari = unwrap(dataSource);
        if (hikari == null || hikari.isRunning()) {
            return hikari != null;
        }
        Thread thread = Thread.ofPlatform().daemon().name("db-pool-warmup").start(() -> {
            try (Connection ignored = hikari.getConnection()) {
                log.debug("Database connection pool {} started", hikari.getPoolName());
            } catch (SQLException e) {
                // SQLState and class only: messages can carry the JDBC URL
                log.warn("Database unreachable at startup ({}, SQLState {}); the pool keeps retrying and "
                        + "requests that need it fail until it is back", e.getClass().getSimpleName(), e.getSQLState());
            }
        });
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (!hikari.isRunning() && thread.isAlive() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return hikari.isRunning();
    }

    private static HikariDataSource unwrap(DataSource dataSource) {
        try {
            return dataSource.isWrapperFor(HikariDataSource.class) ? dataSource.unwrap(HikariDataSource.class) : null;
        } catch (SQLException e) {
            return null;
        }
    }
}
