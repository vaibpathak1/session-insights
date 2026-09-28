package io.sessioninsights.processor.ingest;

/**
 * A record that can never be written and must go to the DLT. The message is the reason code
 * only: nothing from the payload.
 */
public class PoisonRecordException extends RuntimeException {

    private final DltReason reason;

    public PoisonRecordException(DltReason reason) {
        super(reason.code(), null, false, false);
        this.reason = reason;
    }

    public DltReason reason() {
        return reason;
    }

    /** The reason carried anywhere in the cause chain, or null. */
    public static DltReason reasonOf(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PoisonRecordException poison) {
                return poison.reason();
            }
        }
        return null;
    }
}
