package io.sessioninsights.processor.store;

/**
 * The store is up and rejected the data itself (a parse, type, value or constraint error).
 * Only thrown after a successful ping, so an outage cannot masquerade as bad data (ADR-0011).
 * The batch is bisected to find the offending records, which are dead-lettered as
 * {@code store_rejected}.
 * <p>
 * Carries the store's error code only, never the message, which can quote the rejected row.
 */
public class StoreRejectedException extends RuntimeException {

    private final Store store;
    private final String code;

    /** A ClickHouse server error code. */
    public StoreRejectedException(int errorCode) {
        this(Store.CLICKHOUSE, Integer.toString(errorCode));
    }

    /** {@code code}: e.g. a ClickHouse error code or a PostgreSQL SQLSTATE. */
    public StoreRejectedException(Store store, String code) {
        super(store.tag() + " rejected the data (code " + code + ")", null, false, false);
        this.store = store;
        this.code = code;
    }

    public Store store() {
        return store;
    }

    public String code() {
        return code;
    }

    /** The numeric ClickHouse error code, or 0 for other stores. */
    public int errorCode() {
        try {
            return store == Store.CLICKHOUSE ? Integer.parseInt(code) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
