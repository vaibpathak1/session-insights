package io.sessioninsights.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.query.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, Flyway-style migrator for ClickHouse (Phase 1, task 1.1).
 *
 * <ul>
 *   <li>Migrations are classpath files {@code V<n>__<name>.sql}, applied in numeric order.</li>
 *   <li>Each applied migration is recorded in {@code schema_migrations} with a SHA-256
 *       checksum; a changed file, an unknown applied version, or a pending version lower
 *       than the latest applied one fails the run.</li>
 *   <li>A file may contain several statements separated by {@code ;} at end of line.
 *       ClickHouse DDL is not transactional: a migration is recorded only after all its
 *       statements succeed, so statements should be idempotent ({@code IF NOT EXISTS}).</li>
 * </ul>
 *
 * Not safe for concurrent runs from several processes; in v1 only api-service runs it.
 */
public final class ClickHouseMigrator {

    public static final String DEFAULT_LOCATION = "db/migration/clickhouse";
    static final String HISTORY_TABLE = "schema_migrations";

    private static final Logger log = LoggerFactory.getLogger(ClickHouseMigrator.class);
    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__(\\w+)\\.sql");
    private static final Pattern STATEMENT_END = Pattern.compile(";\\s*$", Pattern.MULTILINE);
    private static final long STATEMENT_TIMEOUT_SECONDS = 300;

    private final Client client;
    private final String location;
    private final ReentrantLock lock = new ReentrantLock();

    public ClickHouseMigrator(Client client) {
        this(client, DEFAULT_LOCATION);
    }

    public ClickHouseMigrator(Client client, String location) {
        this.client = client;
        this.location = location;
    }

    /** Applies pending migrations and returns how many were applied. */
    public int migrate() {
        lock.lock();
        try {
            execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        version    UInt32,
                        name       String,
                        checksum   String,
                        applied_at DateTime64(3) DEFAULT now64(3)
                    ) ENGINE = MergeTree ORDER BY version""".formatted(HISTORY_TABLE));

            List<Migration> available = discover();
            Map<Integer, AppliedMigration> applied = loadApplied();
            validate(available, applied);

            int maxApplied = applied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            int count = 0;
            for (Migration migration : available) {
                if (applied.containsKey(migration.version())) {
                    continue;
                }
                if (migration.version() < maxApplied) {
                    throw new MigrationException("Pending migration V%d is older than applied V%d"
                            .formatted(migration.version(), maxApplied));
                }
                apply(migration);
                count++;
            }
            log.info("ClickHouse migrations: {} applied, {} total", count, available.size());
            return count;
        } finally {
            lock.unlock();
        }
    }

    private void apply(Migration migration) {
        log.info("Applying ClickHouse migration V{}__{}", migration.version(), migration.name());
        for (String statement : migration.statements()) {
            execute(statement);
        }
        execute("INSERT INTO " + HISTORY_TABLE + " (version, name, checksum)"
                        + " VALUES ({version:UInt32}, {name:String}, {checksum:String})",
                Map.of("version", migration.version(), "name", migration.name(),
                        "checksum", migration.checksum()));
    }

    private void validate(List<Migration> available, Map<Integer, AppliedMigration> applied) {
        Map<Integer, Migration> byVersion = new LinkedHashMap<>();
        for (Migration migration : available) {
            if (byVersion.putIfAbsent(migration.version(), migration) != null) {
                throw new MigrationException("Duplicate ClickHouse migration version V" + migration.version());
            }
        }
        for (AppliedMigration done : applied.values()) {
            Migration local = byVersion.get(done.version());
            if (local == null) {
                throw new MigrationException("Applied migration V%d__%s not found in %s"
                        .formatted(done.version(), done.name(), location));
            }
            if (!local.checksum().equals(done.checksum())) {
                throw new MigrationException("Checksum mismatch for V%d__%s: applied %s, local %s"
                        .formatted(done.version(), done.name(), done.checksum(), local.checksum()));
            }
        }
    }

    private Map<Integer, AppliedMigration> loadApplied() {
        Map<Integer, AppliedMigration> applied = new LinkedHashMap<>();
        for (GenericRecord row : client.queryAll(
                "SELECT version, name, checksum FROM " + HISTORY_TABLE + " ORDER BY version")) {
            int version = (int) row.getLong("version");
            applied.put(version, new AppliedMigration(version, row.getString("name"), row.getString("checksum")));
        }
        return applied;
    }

    private List<Migration> discover() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + location + "/V*__*.sql");
            List<Migration> migrations = new ArrayList<>();
            for (Resource resource : resources) {
                Matcher matcher = FILE_NAME.matcher(String.valueOf(resource.getFilename()));
                if (!matcher.matches()) {
                    throw new MigrationException("Invalid ClickHouse migration file name: " + resource.getFilename());
                }
                String sql = resource.getContentAsString(StandardCharsets.UTF_8);
                migrations.add(new Migration(Integer.parseInt(matcher.group(1)), matcher.group(2),
                        sha256(sql), split(sql)));
            }
            migrations.sort(Comparator.comparingInt(Migration::version));
            return migrations;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read ClickHouse migrations from " + location, e);
        }
    }

    static List<String> split(String sql) {
        String withoutComments = sql.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .reduce("", (a, b) -> a + b + "\n");
        return Arrays.stream(STATEMENT_END.split(withoutComments))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void execute(String sql) {
        execute(sql, Map.of());
    }

    private void execute(String sql, Map<String, Object> params) {
        try (CommandResponse ignored = client.execute(sql, params).get(STATEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            // response carries only summary stats
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MigrationException("Interrupted while executing ClickHouse statement", e);
        } catch (Exception e) {
            throw new MigrationException("ClickHouse statement failed: " + sql, e);
        }
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Migration(int version, String name, String checksum, List<String> statements) {
    }

    record AppliedMigration(int version, String name, String checksum) {
    }

    public static final class MigrationException extends RuntimeException {
        public MigrationException(String message) {
            super(message);
        }

        public MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
