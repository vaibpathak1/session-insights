package io.sessioninsights.api.config;

import com.clickhouse.client.api.Client;
import io.sessioninsights.db.ClickHouseMigrator;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClickHouseProperties.class)
class ClickHouseConfig {

    @Bean(destroyMethod = "close")
    Client clickHouseClient(ClickHouseProperties properties) {
        return new Client.Builder()
                .addEndpoint(properties.endpoint())
                .setUsername(properties.username())
                .setPassword(properties.password())
                .setDefaultDatabase(properties.database())
                .build();
    }

    @Bean
    ClickHouseMigrator clickHouseMigrator(Client clickHouseClient) {
        return new ClickHouseMigrator(clickHouseClient);
    }

    /** Applies ClickHouse migrations during startup, like Boot's Flyway initializer does for PostgreSQL. */
    @Bean
    InitializingBean clickHouseMigration(ClickHouseMigrator clickHouseMigrator) {
        return clickHouseMigrator::migrate;
    }
}
