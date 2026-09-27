package io.sessioninsights.collector.web;

import io.sessioninsights.collector.CollectorMetrics;
import io.sessioninsights.collector.ingest.IngestException;
import io.sessioninsights.collector.ingest.Rejection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps refusals to {@code {"error":"<code>"}}. No detail that would help probe keys or the
 * validator, and nothing from the request is logged.
 */
@RestControllerAdvice
public class IngestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestExceptionHandler.class);
    /** Suggested back-off when the key database is unreachable. */
    private static final long DATABASE_RETRY_AFTER_SECONDS = 5;

    private final CollectorMetrics metrics;

    public IngestExceptionHandler(CollectorMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(IngestException.class)
    public ResponseEntity<String> refused(IngestException e) {
        return respond(e.rejection(), e.retryAfterSeconds());
    }

    /** Key lookup failed (PostgreSQL down): the client should retry, not drop the batch. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> databaseUnavailable(DataAccessException e) {
        log.warn("Site key lookup failed: {}", e.getClass().getSimpleName());
        return respond(Rejection.UNAVAILABLE, DATABASE_RETRY_AFTER_SECONDS);
    }

    private ResponseEntity<String> respond(Rejection rejection, long retryAfterSeconds) {
        metrics.rejected(rejection);
        var response = ResponseEntity.status(rejection.status()).contentType(MediaType.APPLICATION_JSON);
        if (retryAfterSeconds > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        // a String body gets an exact Content-Length: when Tomcat closes the connection after
        // refusing a large unread body, the client can still read the whole response
        return response.body("{\"error\":\"" + rejection.code() + "\"}");
    }
}
