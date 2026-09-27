package io.sessioninsights.collector.ingest;

/**
 * A refused request. Carries no message and no cause text on purpose: nothing from the
 * payload or key may reach logs or the response.
 */
public class IngestException extends RuntimeException {

    private final Rejection rejection;
    private final long retryAfterSeconds;

    public IngestException(Rejection rejection) {
        this(rejection, 0);
    }

    public IngestException(Rejection rejection, long retryAfterSeconds) {
        super(rejection.reason(), null, false, false);
        this.rejection = rejection;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Rejection rejection() {
        return rejection;
    }

    /** Seconds for the {@code Retry-After} header; 0 when not applicable. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
