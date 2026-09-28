package io.sessioninsights.processor.store;

/** The stores the processor writes to; used as a low-cardinality metric and log tag. */
public enum Store {
    CLICKHOUSE("clickhouse"),
    S3("s3"),
    POSTGRES("postgres"),
    /** An unexpected failure that no store classified (a bug); handled like an outage. */
    UNKNOWN("unknown");

    private final String tag;

    Store(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
