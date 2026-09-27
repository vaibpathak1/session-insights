package io.sessioninsights.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("clickhouse")
public record ClickHouseProperties(String endpoint, String database, String username, String password) {
}
