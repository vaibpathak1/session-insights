package io.sessioninsights.collector.ingest;

import org.springframework.http.HttpStatus;

/**
 * Why a request was refused. {@link #code()} is the whole error body and the metric tag; it
 * says nothing that would help probe keys or the validator.
 */
public enum Rejection {
    MISSING_KEY(HttpStatus.UNAUTHORIZED, "unauthorized"),
    UNKNOWN_KEY(HttpStatus.UNAUTHORIZED, "unauthorized"),
    ORIGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "forbidden"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate_limited"),
    TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "too_large"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type"),
    INVALID(HttpStatus.BAD_REQUEST, "invalid"),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");

    private final HttpStatus status;
    private final String code;

    Rejection(HttpStatus status, String code) {
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Metric tag value, finer-grained than {@link #code()}. */
    public String reason() {
        return name().toLowerCase();
    }
}
