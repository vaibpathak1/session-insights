package io.sessioninsights.processor.store;

import io.sessioninsights.events.EventRow;

import java.util.List;

/**
 * Persistence of structured telemetry events (ADR-0002). Write side only; reads live in
 * {@code platform-events} ({@code EventReader}), shared with api-service.
 */
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
}
