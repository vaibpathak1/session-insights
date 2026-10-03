package io.sessioninsights.api;

import io.sessioninsights.db.testing.TestContainers;
import io.sessioninsights.db.testing.TestImages;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * PostgreSQL, ClickHouse and SeaweedFS started once per JVM and shared by all api-service
 * integration tests. Tests run the {@code dev} profile (the only one that starts, ADR-0013)
 * with a test password.
 */
@ActiveProfiles("dev")
public abstract class ApiIntegrationTest {

    public static final String DEV_PASSWORD = "test-dev-password-0123";
    public static final String BUCKET = "session-replays";

    protected static final PostgreSQLContainer POSTGRES = TestContainers.postgres();
    protected static final GenericContainer<?> CLICKHOUSE = TestContainers.clickhouse();
    protected static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(TestImages.SEAWEEDFS)
            .withCopyFileToContainer(MountableFile.forHostPath(repoRoot().resolve("infra/seaweedfs/s3.json")),
                    "/etc/seaweedfs/s3.json")
            .withCommand("server", "-dir=/data", "-ip=127.0.0.1", "-ip.bind=0.0.0.0", "-master.volumeSizeLimitMB=64",
                    "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json")
            .withExposedPorts(8333, 9333)
            .waitingFor(Wait.forHttp("/cluster/status").forPort(9333));
    protected static final S3Client S3;

    static {
        POSTGRES.start();
        CLICKHOUSE.start();
        SEAWEEDFS.start();
        S3 = S3Client.builder()
                .endpointOverride(URI.create(s3Endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("insights_dev_key", "insights_dev_secret")))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                S3.createBucket(b -> b.bucket(BUCKET));
                break;
            } catch (RuntimeException e) {   // S3 gateway still starting
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
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
        registry.add("s3.endpoint", ApiIntegrationTest::s3Endpoint);
        registry.add("api.dev-auth.password", () -> DEV_PASSWORD);
    }

    static String s3Endpoint() {
        return "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333);
    }

    protected static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".env.example"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(".env.example not found");
        }
        return dir;
    }
}
