package io.sessioninsights.collector.ingest;

import java.io.InputStream;

/**
 * One inbound request, independent of the servlet API.
 *
 * @param key             site key from {@code X-SI-Key} or {@code ?k=}; {@code null} if neither
 * @param userAgent       the {@code User-Agent} header, or {@code null}
 * @param onOriginAllowed called once the origin has matched the site, so CORS headers are
 *                        present on every later response, errors included
 */
public record IngestRequest(
        String key,
        String origin,
        String contentEncoding,
        long contentLength,
        InputStream body,
        Runnable onOriginAllowed,
        String userAgent) {

    @Override
    public String toString() {
        return "IngestRequest[origin=" + origin + ", contentLength=" + contentLength + "]";   // never the key
    }
}
