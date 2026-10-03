package io.sessioninsights.api.config;

import com.clickhouse.client.api.Client;
import io.sessioninsights.events.EventReader;
import io.sessioninsights.events.ManifestReader;
import io.sessioninsights.events.ReplayObjectReader;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/** The telemetry read side (platform-events): ClickHouse events and manifest, replay objects in S3. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReadSideConfig.S3Properties.class)
class ReadSideConfig {

    /** S3-compatible replay storage, read-only here (path-style, e.g. SeaweedFS). */
    @ConfigurationProperties("s3")
    record S3Properties(String endpoint, String region, String bucket, String accessKey, String secretKey) {
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(S3Properties s3) {
        return S3Client.builder()
                .endpointOverride(URI.create(s3.endpoint()))
                .region(Region.of(s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Bean
    EventReader eventReader(Client clickHouseClient) {
        return new EventReader(clickHouseClient);
    }

    @Bean
    ManifestReader manifestReader(Client clickHouseClient) {
        return new ManifestReader(clickHouseClient);
    }

    @Bean
    ReplayObjectReader replayObjectReader(S3Client s3Client, S3Properties s3) {
        return new ReplayObjectReader(s3Client, s3.bucket());
    }
}
