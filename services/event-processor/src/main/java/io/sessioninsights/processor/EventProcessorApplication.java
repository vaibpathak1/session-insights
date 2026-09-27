package io.sessioninsights.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Kafka consumers: writes events to ClickHouse, replay chunks to S3, tracks session lifecycle. */
@SpringBootApplication
public class EventProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventProcessorApplication.class, args);
    }
}
