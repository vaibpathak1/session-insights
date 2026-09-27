package io.sessioninsights.collector;

import io.sessioninsights.collector.tenant.SiteKeyHash;
import io.sessioninsights.common.Topics;
import io.sessioninsights.db.testing.TestContainers;
import io.sessioninsights.db.testing.TestImages;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * PostgreSQL (pgvector image) and Kafka (compose image), started once per JVM. Migrations
 * run as the owner; the collector connects as {@code insights_collector}, exactly as in
 * compose. Topics are declared like {@code infra/kafka/create-topics.sh}.
 */
public final class CollectorTestInfra {

    public static final PostgreSQLContainer POSTGRES = TestContainers.postgres();
    public static final KafkaContainer KAFKA = new KafkaContainer(TestImages.KAFKA);

    static {
        POSTGRES.start();
        KAFKA.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/postgres")
                .placeholders(TestContainers.flywayPlaceholders())
                .load()
                .migrate();
        createTopics();
    }

    private CollectorTestInfra() {
    }

    /** A site with one key; {@code key} is the plaintext the SDK would send. */
    public record SiteFixture(UUID tenantId, UUID siteId, String key) {
        public String keyHash() {
            return SiteKeyHash.of(key);
        }
    }

    public static void registerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> TestContainers.COLLECTOR_USER);
        registry.add("spring.datasource.password", () -> TestContainers.COLLECTOR_PASSWORD);
    }

    public static void registerKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    public static SiteFixture newSite(String... allowedOrigins) {
        UUID tenant = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        String key = "sk_test_" + UUID.randomUUID();
        JdbcClient db = owner();
        db.sql("INSERT INTO tenant (id, name) VALUES (?, 'collector-test')").params(tenant).update();
        db.sql("INSERT INTO site (id, tenant_id, name, allowed_origins) VALUES (?, ?, 'site', ?)")
                .params(site, tenant, allowedOrigins).update();
        db.sql("INSERT INTO site_key (tenant_id, site_id, key_prefix, key_hash) VALUES (?, ?, ?, ?)")
                .params(tenant, site, key.substring(0, 12), SiteKeyHash.of(key)).update();
        return new SiteFixture(tenant, site, key);
    }

    public static void revoke(SiteFixture site) {
        owner().sql("UPDATE site_key SET revoked_at = now() WHERE key_hash = ?").params(site.keyHash()).update();
    }

    /** Superuser connection: bypasses RLS, for arranging fixtures. */
    public static JdbcClient owner() {
        return JdbcClient.create(new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true));
    }

    /** Reads every record with {@code key} from the topic, waiting until {@code expected} arrived. */
    public static List<ConsumerRecord<String, byte[]>> records(String topic, String key, int expected) {
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
            }
            // one more short poll so tests also catch records beyond the expected count
            consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (key.equals(r.key())) {
                    found.add(r);
                }
            });
            return found;
        }
    }

    private static void createTopics() {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(Topics.TELEMETRY_EVENTS, 3, (short) 1),
                    new NewTopic(Topics.REPLAY_CHUNKS, 3, (short) 1)
                            .configs(Map.of("max.message.bytes", "4194304")))).all().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("creating test topics failed", e);
        }
    }
}
