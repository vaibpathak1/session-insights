package io.sessioninsights.collector.config;

import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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
}
