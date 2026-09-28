package io.sessioninsights.processor.config;

import com.clickhouse.client.api.Client;
import io.sessioninsights.db.PoolWarmUp;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.sessions.SessionCloser;
import io.sessioninsights.processor.sessions.SessionCloserSchedule;
import io.sessioninsights.processor.sessions.SessionStats;
import io.sessioninsights.processor.sessions.SessionStore;
import io.sessioninsights.processor.sessions.UserAgents;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * Session lifecycle in PostgreSQL (Phase 5): plain JDBC as {@code insights_processor}, no JPA,
 * no migrations (api-service runs them).
 */
@Configuration(proxyBeanMethods = false)
class SessionConfig {

    @Bean
    SessionStore sessionStore(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        return new SessionStore(jdbcClient, transactionTemplate);
    }

    @Bean
    SessionStats sessionStats(Client clickHouseClient) {
        return new SessionStats(clickHouseClient);
    }

    @Bean
    SessionCloser sessionCloser(JdbcClient jdbcClient, TransactionTemplate transactionTemplate, SessionStats sessionStats,
                                KafkaTemplate<String, byte[]> kafkaTemplate, ProcessorMetrics metrics,
                                ProcessorProperties properties) {
        ProcessorProperties.Sessions sessions = properties.sessions();
        ProcessorProperties.Closer closer = sessions.closer();
        return new SessionCloser(jdbcClient, transactionTemplate, sessionStats, kafkaTemplate, metrics,
                sessions.idleTimeout(), closer.batchSize(), closer.statementTimeout(), closer.publishTimeout(),
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "processor.sessions.closer.enabled", havingValue = "true", matchIfMissing = true)
    SessionCloserSchedule sessionCloserSchedule(SessionCloser sessionCloser, ProcessorMetrics metrics,
                                                ProcessorProperties properties) {
        return new SessionCloserSchedule(sessionCloser, metrics, properties.sessions().closer().interval());
    }

    @Bean
    UserAgents userAgents() {
        return new UserAgents();
    }

    /** Pool created before any virtual thread can need it (task 5.0); non-fatal if PostgreSQL is down. */
    @Bean
    SmartInitializingSingleton connectionPoolWarmUp(DataSource dataSource) {
        return () -> PoolWarmUp.warmUp(dataSource, Duration.ofSeconds(2));
    }
}
