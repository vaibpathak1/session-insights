package io.sessioninsights.collector.config;

import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CollectorConfiguration {

    /** Clock for the site key cache; tests replace it to step past TTLs deterministically. */
    @Bean
    Ticker cacheTicker() {
        return Ticker.systemTicker();
    }
}
