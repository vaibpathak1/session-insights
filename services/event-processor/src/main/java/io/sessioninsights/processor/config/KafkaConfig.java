package io.sessioninsights.processor.config;

import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.kafka.ConsumerErrorHandler;
import io.sessioninsights.processor.kafka.DeadLetters;
import io.sessioninsights.processor.kafka.StoreOutageTracker;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/**
 * Batch listeners with manual commit after a successful write (AckMode.BATCH, see
 * {@code application.yml}) and the ADR-0011 error handler.
 * <p>
 * <b>Consumer threads are platform threads</b> (ADR-0011): kafka-clients' classic consumer
 * does blocking work inside {@code synchronized} (group coordinator, metadata), which pins a
 * virtual thread on Java 21; with more consumers than carrier threads that deadlocked. There
 * is one thread per partition consumer (a small, fixed number); our own blocking work inside
 * a batch (parallel object PUTs) still uses virtual threads.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    static final String CONSUMER_THREAD_PREFIX = "kafka-consumer-";

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        // replaces the virtual-thread executor Boot configures with spring.threads.virtual.enabled
        SimpleAsyncTaskExecutor platformThreads = new SimpleAsyncTaskExecutor(CONSUMER_THREAD_PREFIX);
        platformThreads.setVirtualThreads(false);
        factory.setContainerCustomizer(container -> container.getContainerProperties().setListenerTaskExecutor(platformThreads));
        return factory;
    }

    /** Batch dead-lettering for the listeners (ADR-0014); acks awaited for at most 30 s. */
    @Bean
    DeadLetters deadLetters(KafkaTemplate<String, byte[]> template, ProcessorMetrics metrics) {
        return new DeadLetters(template, metrics, Duration.ofSeconds(30));
    }

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
