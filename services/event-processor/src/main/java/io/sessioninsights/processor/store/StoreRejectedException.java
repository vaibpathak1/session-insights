package io.sessioninsights.processor.store;

/**
 * The store is up and rejected the data itself (a parse, type or value error). Only thrown
 * after a successful ping, so an outage cannot masquerade as bad data (ADR-0011). The batch
 * is bisected to find the offending records, which are dead-lettered as {@code store_rejected}.
 * <p>
 * Carries the server error code only, never the message, which quotes the rejected row.
 */
public class StoreRejectedException extends RuntimeException {

    private final int errorCode;

    public StoreRejectedException(int errorCode) {
        super("ClickHouse rejected the data (code " + errorCode + ")", null, false, false);
        this.errorCode = errorCode;
    }

    public int errorCode() {
        return errorCode;
    }
}
