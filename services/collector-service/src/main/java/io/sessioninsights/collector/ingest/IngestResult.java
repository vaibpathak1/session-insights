package io.sessioninsights.collector.ingest;

/** {@code 202} body: events (or chunks) written to Kafka, and events dropped for their timestamp. */
public record IngestResult(int accepted, int dropped) {
}
