package io.sessioninsights.processor.config;

import io.sessioninsights.db.PoolWarmUp;
import io.sessioninsights.processor.sessions.SessionStore;
import io.sessioninsights.processor.sessions.UserAgents;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
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
    UserAgents userAgents() {
        return new UserAgents();
    }

    /** Pool created before any virtual thread can need it (task 5.0); non-fatal if PostgreSQL is down. */
    @Bean
    SmartInitializingSingleton connectionPoolWarmUp(DataSource dataSource) {
        return () -> PoolWarmUp.warmUp(dataSource, Duration.ofSeconds(2));
    }
}
