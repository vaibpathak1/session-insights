package io.sessioninsights.collector.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Reads a request body into memory with a hard limit on the <em>decompressed</em> size.
 * Decompression stops one byte past the limit, so a gzip bomb costs at most {@code limit}
 * bytes of work before it is refused with {@code 413}.
 */
public final class RequestBodyReader {

    private RequestBodyReader() {
    }

    /**
     * @param contentEncoding {@code null}, {@code identity} or {@code gzip}; anything else is 415
     * @param contentLength   declared length, or -1 if unknown
     */
    public static byte[] read(InputStream body, String contentEncoding, long contentLength, long limit) {
        boolean gzip = isGzip(contentEncoding);
        if (!gzip && contentLength > limit) {
            throw new IngestException(Rejection.TOO_LARGE);
        }
        try (InputStream in = gzip ? new GZIPInputStream(body) : body) {
            byte[] bytes = in.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) {
                throw new IngestException(Rejection.TOO_LARGE);
            }
            return bytes;
        } catch (IOException malformedOrTruncated) {   // incl. ZipException, EOFException
            throw new IngestException(Rejection.INVALID);
        }
    }

    private static boolean isGzip(String contentEncoding) {
        if (contentEncoding == null || contentEncoding.isBlank() || contentEncoding.equalsIgnoreCase("identity")) {
            return false;
        }
        if (contentEncoding.trim().equalsIgnoreCase("gzip")) {
            return true;
        }
        throw new IngestException(Rejection.UNSUPPORTED_MEDIA_TYPE);
    }
}
