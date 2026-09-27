package io.sessioninsights.collector.web;

import io.sessioninsights.collector.ingest.IngestException;
import io.sessioninsights.collector.ingest.IngestRequest;
import io.sessioninsights.collector.ingest.IngestResult;
import io.sessioninsights.collector.ingest.IngestService;
import io.sessioninsights.collector.ingest.Rejection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.function.Function;

/**
 * SDK ingestion endpoints (task 2.4). The body is read by the service, not by message
 * converters, so JSON and {@code text/plain} (sent by {@code navigator.sendBeacon} to avoid
 * a preflight) share one size-limited, gzip-aware path. Preflights are answered by
 * {@link CorsPreflightFilter}.
 */
@RestController
@RequestMapping("/v1")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public ResponseEntity<IngestResult> events(HttpServletRequest request, HttpServletResponse response) throws IOException {
        return handle(request, response, service::events);
    }

    @PostMapping("/replay")
    public ResponseEntity<IngestResult> replay(HttpServletRequest request, HttpServletResponse response) throws IOException {
        return handle(request, response, service::replay);
    }

    private static ResponseEntity<IngestResult> handle(HttpServletRequest request, HttpServletResponse response,
                                                       Function<IngestRequest, IngestResult> pipeline) throws IOException {
        CorsHeaders.vary(response);
        requireSupportedContentType(request.getContentType());
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        IngestRequest ingest = new IngestRequest(
                siteKey(request),
                origin,
                request.getHeader(HttpHeaders.CONTENT_ENCODING),
                request.getContentLengthLong(),
                request.getInputStream(),
                () -> CorsHeaders.allow(response, origin));
        return ResponseEntity.accepted().body(pipeline.apply(ingest));
    }

    /**
     * Header first ({@code X-SI-Key}, non-browser clients only), then {@code ?k=} (how browser
     * clients must send it, ADR-0010); the body is the last resort.
     */
    private static String siteKey(HttpServletRequest request) {
        String header = request.getHeader(CorsHeaders.KEY_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String query = request.getParameter(CorsHeaders.KEY_PARAM);   // query only: form bodies are refused below
        return query == null || query.isBlank() ? null : query.trim();
    }

    private static void requireSupportedContentType(String contentType) {
        if (contentType == null) {
            return;   // a beacon with a Blob body may omit it; the body must still be JSON
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            if (type.isCompatibleWith(MediaType.APPLICATION_JSON) || type.isCompatibleWith(MediaType.TEXT_PLAIN)) {
                return;
            }
        } catch (InvalidMediaTypeException ignored) {
            // fall through
        }
        throw new IngestException(Rejection.UNSUPPORTED_MEDIA_TYPE);
    }
}
