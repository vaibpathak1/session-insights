package io.sessioninsights.collector.config;

import com.github.benmanes.caffeine.cache.Ticker;
import io.sessioninsights.db.PoolWarmUp;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class CollectorConfiguration {

    /** Clock for the site key cache; tests replace it to step past TTLs deterministically. */
    @Bean
    Ticker cacheTicker() {
        return Ticker.systemTicker();
    }

    /** Server receive time ({@code receivedAt}) and the reference for the event time window. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Creates the connection pool before the web server starts, on a platform thread, so the
     * first request's virtual thread never creates it inside {@code synchronized} (task 5.0).
     * Non-fatal: with PostgreSQL down the collector still starts and answers 503.
     */
    @Bean
    SmartInitializingSingleton connectionPoolWarmUp(DataSource dataSource) {
        return () -> PoolWarmUp.warmUp(dataSource, Duration.ofSeconds(2));
    }
}
