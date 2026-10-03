package io.sessioninsights.processor.config;

import com.clickhouse.client.api.Client;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.events.EventReader;
import io.sessioninsights.events.ManifestReader;
import io.sessioninsights.events.ReplayObjectReader;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.store.ClickHouseEventStore;
import io.sessioninsights.processor.store.ClickHouseInserter;
import io.sessioninsights.processor.store.ClickHouseReplayManifestStore;
import io.sessioninsights.processor.store.EventStore;
import io.sessioninsights.processor.store.ReplayManifestStore;
import io.sessioninsights.processor.store.ReplayObjectStore;
import io.sessioninsights.processor.store.S3ReplayObjectStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.temporal.ChronoUnit;

/**
 * ClickHouse and S3 clients and the stores built on them. The processor does not run
 * migrations (api-service does) and never touches PostgreSQL.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProcessorProperties.class)
class StoreConfig {

    @Bean(destroyMethod = "close")
    Client clickHouseClient(ProcessorProperties properties) {
        ProcessorProperties.ClickHouse ch = properties.clickhouse();
        return new Client.Builder()
                .addEndpoint(ch.endpoint())
                .setUsername(ch.username())
                .setPassword(ch.password())
                .setDefaultDatabase(ch.database())
                .setConnectTimeout(ch.connectTimeout().toMillis(), ChronoUnit.MILLIS)
                .setSocketTimeout(ch.socketTimeout().toMillis(), ChronoUnit.MILLIS)
                .build();
    }

    @Bean
    ClickHouseInserter clickHouseInserter(Client clickHouseClient, ProcessorProperties properties,
                                          ProcessorMetrics metrics) {
        return new ClickHouseInserter(clickHouseClient, WireJson.mapper(), properties.clickhouse().insertTimeout(),
                metrics);
    }

    @Bean
    EventStore eventStore(ClickHouseInserter inserter) {
        return new ClickHouseEventStore(inserter);
    }

    @Bean
    ReplayManifestStore replayManifestStore(ClickHouseInserter inserter) {
        return new ClickHouseReplayManifestStore(inserter);
    }

    // read side (platform-events): used by tests here, and by api-service
    @Bean
    EventReader eventReader(Client clickHouseClient) {
        return new EventReader(clickHouseClient);
    }

    @Bean
    ManifestReader manifestReader(Client clickHouseClient) {
        return new ManifestReader(clickHouseClient);
    }

    @Bean
    ReplayObjectReader replayObjectReader(S3Client s3Client, ProcessorProperties properties) {
        return new ReplayObjectReader(s3Client, properties.s3().bucket());
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(ProcessorProperties properties) {
        ProcessorProperties.S3 s3 = properties.s3();
        return S3Client.builder()
                .endpointOverride(URI.create(s3.endpoint()))
                .region(Region.of(s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                .forcePathStyle(true)
                // SDK >= 2.30 adds CRC checksums to every request by default; not every
                // S3-compatible store supports them, and S3 itself does not require them
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(Apache5HttpClient.builder().maxConnections(Math.max(s3.maxConcurrency() * 4, 50)))
                .overrideConfiguration(c -> c.apiCallTimeout(s3.apiCallTimeout()))
                .build();
    }

    @Bean
    ReplayObjectStore replayObjectStore(S3Client s3Client, ProcessorProperties properties) {
        return new S3ReplayObjectStore(s3Client, properties.s3().bucket());
    }
}
