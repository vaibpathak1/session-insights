package io.sessioninsights.processor.config;

import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.kafka.ConsumerErrorHandler;
import io.sessioninsights.processor.kafka.DeadLetters;
import io.sessioninsights.processor.kafka.StoreOutageTracker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Batch listeners with manual commit after a successful write (AckMode.BATCH, see
 * {@code application.yml}); Boot applies this error handler and, with
 * {@code spring.threads.virtual.enabled}, a virtual-thread listener executor.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    @Bean
    CommonErrorHandler consumerErrorHandler(KafkaTemplate<String, byte[]> template, ProcessorMetrics metrics,
                                            StoreOutageTracker outages, ProcessorProperties properties) {
        ProcessorProperties.Retry retry = properties.retry();
        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        // no maxElapsedTime / maxAttempts: an outage is waited out, however long (ADR-0011)
        return new ConsumerErrorHandler(DeadLetters.recoverer(template, metrics), backOff, outages);
    }
}
