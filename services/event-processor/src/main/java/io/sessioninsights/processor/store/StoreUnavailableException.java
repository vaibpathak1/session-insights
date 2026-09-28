package io.sessioninsights.processor.store;

/**
 * A store could not take a write: down, unreachable, timing out, overloaded or misconfigured.
 * Never a reason to dead-letter (ADR-0011): the consumer backs off and retries the batch.
 * <p>
 * Carries no cause on purpose: ClickHouse error messages can echo inserted data, and this
 * exception reaches Spring Kafka's logs. {@code detail} is an exception class and error code.
 */
public class StoreUnavailableException extends RuntimeException {

    private final Store store;
    private final String detail;

    public StoreUnavailableException(Store store, String detail) {
        super(store.tag() + " unavailable: " + detail, null, false, false);
        this.store = store;
        this.detail = detail;
    }

    public Store store() {
        return store;
    }

    /** Exception class and status/error code; never payload content. */
    public String detail() {
        return detail;
    }
}
