package io.sessioninsights.processor.store;

import java.util.List;
import java.util.UUID;

/** Persistence of structured telemetry events (ADR-0002). */
public interface EventStore {

    /**
     * Inserts the rows as one batch, all or nothing. Rows of several tenants may share a
     * batch; each carries its own {@code tenantId}.
     *
     * @param deduplicationToken identical for an identical retried batch
     * @throws StoreRejectedException    the store is up and rejected the data
     * @throws StoreUnavailableException the store could not take the write
     */
    void insert(List<EventRow> rows, String deduplicationToken);

    /** A session's events for one tenant, deduplicated, in time order. */
    List<EventRow> findEvents(UUID tenantId, UUID sessionId);
}
