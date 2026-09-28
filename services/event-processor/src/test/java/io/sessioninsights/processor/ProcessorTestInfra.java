package io.sessioninsights.processor;

import com.clickhouse.client.api.Client;
import io.sessioninsights.common.Topics;
import io.sessioninsights.db.ClickHouseMigrator;
import io.sessioninsights.db.testing.TestContainers;
import io.sessioninsights.db.testing.TestImages;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Kafka, ClickHouse (migrated) and SeaweedFS with the replay bucket, started once per JVM
 * with the images from {@code .env.example}. ClickHouse and SeaweedFS listen on fixed host
 * ports so they can be stopped and started again (outage tests) without the processor's
 * endpoints changing; their data survives, as it would on a real restart.
 */
public final class ProcessorTestInfra {

    public static final String S3_ACCESS_KEY = "insights_dev_key";   // infra/seaweedfs/s3.json
    public static final String S3_SECRET_KEY = "insights_dev_secret";
    public static final String BUCKET = "session-replays";
    public static final int PARTITIONS = 3;
    private static final int S3_PORT = 8333;

    public static final KafkaContainer KAFKA = new KafkaContainer(TestImages.KAFKA);
    public static final GenericContainer<?> CLICKHOUSE = TestContainers.clickhouse();
    public static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(TestImages.SEAWEEDFS)
            .withCopyFileToContainer(MountableFile.forHostPath(repoRoot().resolve("infra/seaweedfs/s3.json")),
                    "/etc/seaweedfs/s3.json")
            .withCommand("server", "-dir=/data", "-ip=127.0.0.1", "-ip.bind=0.0.0.0",
                    "-master.volumeSizeLimitMB=64", "-s3", "-s3.port=" + S3_PORT,
                    "-s3.config=/etc/seaweedfs/s3.json")
            .withExposedPorts(S3_PORT, 9333)
            .waitingFor(Wait.forHttp("/cluster/status").forPort(9333));

    public static final Client CLICKHOUSE_CLIENT;
    public static final S3Client S3;

    static {
        CLICKHOUSE.setPortBindings(List.of(freePort() + ":" + TestContainers.CLICKHOUSE_HTTP_PORT));
        SEAWEEDFS.setPortBindings(List.of(freePort() + ":" + S3_PORT, freePort() + ":9333"));
        KAFKA.start();
        CLICKHOUSE.start();
        SEAWEEDFS.start();
        CLICKHOUSE_CLIENT = TestContainers.clickhouseClient(CLICKHOUSE);
        new ClickHouseMigrator(CLICKHOUSE_CLIENT).migrate();
        S3 = S3Client.builder()
                .endpointOverride(URI.create(s3Endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET_KEY)))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        createBucket();
        createTopics();
    }

    private ProcessorTestInfra() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("processor.kafka.concurrency", () -> PARTITIONS);
        registry.add("processor.clickhouse.endpoint", () -> TestContainers.clickhouseEndpoint(CLICKHOUSE));
        registry.add("processor.clickhouse.username", () -> TestContainers.USER);
        registry.add("processor.clickhouse.password", () -> TestContainers.PASSWORD);
        registry.add("processor.clickhouse.database", () -> TestContainers.DATABASE);
        registry.add("processor.clickhouse.socket-timeout", () -> "5s");
        registry.add("processor.clickhouse.insert-timeout", () -> "10s");
        registry.add("processor.s3.endpoint", ProcessorTestInfra::s3Endpoint);
        registry.add("processor.s3.api-call-timeout", () -> "5s");
        registry.add("processor.retry.max-interval", () -> "2s");
    }

    public static String s3Endpoint() {
        return "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(S3_PORT);
    }

    // ---------------------------------------------------------------- outages

    /** {@code docker stop}: connections are refused until {@link #start}. */
    public static void stop(GenericContainer<?> container) {
        DockerClientFactory.instance().client().stopContainerCmd(container.getContainerId()).withTimeout(5).exec();
    }

    /** {@code docker start} of the same container (same data, same host ports), then waits until ready. */
    public static void start(GenericContainer<?> container) {
        DockerClientFactory.instance().client().startContainerCmd(container.getContainerId()).exec();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (container == CLICKHOUSE ? CLICKHOUSE_CLIENT.ping(1000) : s3Ready()) {
                return;
            }
            sleep(250);
        }
        throw new IllegalStateException("container did not come back: " + container.getDockerImageName());
    }

    private static boolean s3Ready() {
        try {
            S3.headBucket(b -> b.bucket(BUCKET));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Kafka

    public static KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                // like the collector: 16 MB chunks, compressed so they fit the 4 MB topic
                ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd",
                ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 17825792),
                new StringSerializer(), new ByteArraySerializer());
    }

    /** Sends all records and waits for their acks; returns their metadata in order. */
    public static List<RecordMetadata> send(List<ProducerRecord<String, byte[]>> records) {
        try (var producer = producer()) {
            var futures = records.stream().map(producer::send).toList();
            List<RecordMetadata> sent = new ArrayList<>();
            for (var future : futures) {
                sent.add(future.get());
            }
            return sent;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every record with {@code key} on the topic, waiting up to {@code wait} for {@code expected} of them. */
    public static List<ConsumerRecord<String, byte[]>> records(String topic, String key, int expected, Duration wait) {
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
            long deadline = System.nanoTime() + wait.toNanos();
            do {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
            } while (found.size() < expected && System.nanoTime() < deadline);
            // one more short poll so tests also catch records beyond the expected count
            consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (key.equals(r.key())) {
                    found.add(r);
                }
            });
            return found;
        }
    }

    /** The group's committed offset for a partition, or -1 if none. */
    public static long committedOffset(String groupId, TopicPartition partition) {
        try (Admin admin = admin()) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata().get().get(partition);
            return committed == null ? -1 : committed.offset();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    /** Same topics and settings as infra/kafka/create-topics.sh, with fewer partitions. */
    private static void createTopics() {
        Map<String, String> replay = Map.of("max.message.bytes", "4194304");
        try (Admin admin = admin()) {
            admin.createTopics(List.of(
                    new NewTopic(Topics.TELEMETRY_EVENTS, PARTITIONS, (short) 1),
                    new NewTopic(Topics.TELEMETRY_EVENTS_DLT, 1, (short) 1),
                    new NewTopic(Topics.REPLAY_CHUNKS, PARTITIONS, (short) 1).configs(replay),
                    new NewTopic(Topics.REPLAY_CHUNKS_DLT, 1, (short) 1).configs(replay))).all().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("creating test topics failed", e);
        }
    }

    private static void createBucket() {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                S3.createBucket(b -> b.bucket(BUCKET));
                return;
            } catch (RuntimeException e) {   // S3 gateway still starting
                last = e;
                sleep(500);
            }
        }
        throw new IllegalStateException("could not create bucket " + BUCKET, last);
    }

    // ------------------------------------------------------------------ misc

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".env.example"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(".env.example not found");
        }
        return dir;
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
