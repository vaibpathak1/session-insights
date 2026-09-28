package io.sessioninsights.processor.ingest;

import io.sessioninsights.common.Topics;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.store.ManifestRow;
import io.sessioninsights.processor.store.ReplayManifestStore;
import io.sessioninsights.processor.store.ReplayObjectStore;
import io.sessioninsights.processor.store.Store;
import io.sessioninsights.processor.store.StoreUnavailableException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * {@code replay.chunks.v1} → object storage + {@code replay_chunks} manifest (task 4.4).
 * All objects of the batch are written first (in parallel, bounded), then one manifest
 * insert, so a manifest row never points to a missing object. A redelivered chunk overwrites
 * the same key with the same bytes.
 */
@Component
public class ReplayListener {

    public static final String ID = "replay";

    private final ReplayObjectStore objects;
    private final ReplayManifestStore manifest;
    private final ProcessorMetrics metrics;
    private final int zstdLevel;
    private final int maxParallelPuts;

    public ReplayListener(ReplayObjectStore objects, ReplayManifestStore manifest, ProcessorMetrics metrics,
                          @Value("${processor.replay.zstd-level}") int zstdLevel,
                          @Value("${processor.s3.max-concurrency}") int maxParallelPuts) {
        this.objects = objects;
        this.manifest = manifest;
        this.metrics = metrics;
        this.zstdLevel = zstdLevel;
        this.maxParallelPuts = maxParallelPuts;
    }

    @KafkaListener(id = ID, topics = Topics.REPLAY_CHUNKS, groupId = "${processor.kafka.replay.group-id}",
            concurrency = "${processor.kafka.concurrency}", batch = "true",
            properties = {
                    "max.poll.records=${processor.kafka.replay.max-poll-records}",
                    "fetch.max.wait.ms=${processor.kafka.replay.fetch-max-wait-ms}"})
    public void onBatch(List<ConsumerRecord<String, byte[]>> records) {
        metrics.batch(Topics.REPLAY_CHUNKS, records.size());
        List<BisectingWriter.Item<ReplayChunk>> chunks = new ArrayList<>(records.size());
        Map<ConsumerRecord<String, byte[]>, DltReason> poison = new LinkedHashMap<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                chunks.add(new BisectingWriter.Item<>(record, ReplayChunk.of(EnvelopeReader.replay(record.value()), zstdLevel)));
            } catch (PoisonRecordException e) {
                poison.put(record, e.reason());
            }
        }
        putAll(chunks.stream().map(BisectingWriter.Item::row).toList());

        List<BisectingWriter.Item<ManifestRow>> rows = chunks.stream()
                .map(c -> new BisectingWriter.Item<>(c.record(), c.row().manifest()))
                .toList();
        for (BisectingWriter.Item<ManifestRow> rejected : BisectingWriter.write(rows, manifest::insert)) {
            // the object stays behind without a manifest row: unreachable, harmless, expires with retention
            poison.put(rejected.record(), DltReason.STORE_REJECTED);
        }
        RecordFailures.raise(records, poison);
    }

    private void putAll(List<ReplayChunk> chunks) {
        Semaphore permits = new Semaphore(maxParallelPuts);
        List<Future<?>> puts = new ArrayList<>(chunks.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ReplayChunk chunk : chunks) {
                puts.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        objects.put(chunk.envelope().tenantId(), chunk.envelope().sessionId(),
                                chunk.envelope().chunkSeq(), chunk.compressed());
                        metrics.chunkStored(chunk.compressed().length);
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
        }
        for (Future<?> put : puts) {
            try {
                put.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof StoreUnavailableException unavailable) {
                    throw unavailable;
                }
                throw new StoreUnavailableException(Store.S3, e.getCause().getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreUnavailableException(Store.S3, "interrupted");
            }
        }
    }
}
