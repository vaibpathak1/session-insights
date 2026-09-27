package io.sessioninsights.collector.ingest;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBodyReaderTest {

    @Test
    void readsPlainAndGzipBodiesWithinTheLimit() throws IOException {
        byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        assertThat(RequestBodyReader.read(new ByteArrayInputStream(json), null, json.length, 7)).isEqualTo(json);
        assertThat(RequestBodyReader.read(new ByteArrayInputStream(gzip(json)), "gzip", -1, 7)).isEqualTo(json);
        assertThat(RequestBodyReader.read(new ByteArrayInputStream(json), "identity", -1, 7)).isEqualTo(json);
    }

    @Test
    void declaredLengthOverLimitIsRefusedBeforeReading() {
        InputStream neverRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("body must not be read");
            }
        };
        assertRejected(() -> RequestBodyReader.read(neverRead, null, 11, 10), Rejection.TOO_LARGE);
    }

    @Test
    void undeclaredLengthOverLimitIsRefused() {
        assertRejected(() -> RequestBodyReader.read(new ByteArrayInputStream(new byte[11]), null, -1, 10), Rejection.TOO_LARGE);
    }

    /** 1 KB of gzip that inflates to 64 MB: decompression must stop just past the limit. */
    @Test
    void gzipBombStopsAtTheLimit() throws IOException {
        byte[] bomb = gzip(new byte[64 * 1024 * 1024]);
        assertThat(bomb.length).isLessThan(128 * 1024);
        CountingStream counted = new CountingStream(new ByteArrayInputStream(bomb));

        assertRejected(() -> RequestBodyReader.read(counted, "gzip", bomb.length, 1024 * 1024), Rejection.TOO_LARGE);
        assertThat(counted.count).as("compressed bytes consumed").isLessThan(bomb.length);
    }

    @Test
    void malformedGzipIsInvalidAndUnknownEncodingUnsupported() {
        assertRejected(() -> RequestBodyReader.read(new ByteArrayInputStream("nope".getBytes()), "gzip", -1, 10), Rejection.INVALID);
        assertRejected(() -> RequestBodyReader.read(new ByteArrayInputStream(new byte[1]), "br", -1, 10),
                Rejection.UNSUPPORTED_MEDIA_TYPE);
    }

    static byte[] gzip(byte[] data) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    static void assertRejected(Runnable call, Rejection expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(IngestException.class,
                e -> assertThat(e.rejection()).isEqualTo(expected));
    }

    static final class CountingStream extends java.io.FilterInputStream {
        long count;

        CountingStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }
}
