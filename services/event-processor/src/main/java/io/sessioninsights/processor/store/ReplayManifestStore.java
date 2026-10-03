package io.sessioninsights.processor.store;

import io.sessioninsights.events.ManifestRow;

import java.util.List;

/**
 * Writes the replay chunk manifest, so the player never needs an object-storage LIST.
 * Reads: {@code platform-events} {@code ManifestReader}.
 */
public interface ReplayManifestStore {

    /** Same contract as {@link EventStore#insert}. */
    void insert(List<ManifestRow> rows, String deduplicationToken);
}
