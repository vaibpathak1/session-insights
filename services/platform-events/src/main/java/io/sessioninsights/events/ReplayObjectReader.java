package io.sessioninsights.events;

import com.github.luben.zstd.ZstdInputStream;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads replay chunk objects from S3-compatible storage (ADR-0003). The key is always built
 * from {@code tenantId}, so a read can only reach that tenant's prefix.
 */
public class ReplayObjectReader {

    private final S3Client s3;
    private final String bucket;

    public ReplayObjectReader(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** The stored (zstd) bytes, or null if there is no such object. */
    public byte[] get(UUID tenantId, UUID sessionId, int chunkSeq) {
        String key = ReplayObjects.objectKey(tenantId, sessionId, chunkSeq);
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key));
            return bytes.asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /**
     * The chunk's JSON (the rrweb event array), decompressed while it is read, so it can be
     * streamed without holding a whole chunk (up to 16 MB) in memory. The caller closes it.
     */
    public Optional<InputStream> openDecompressed(UUID tenantId, UUID sessionId, int chunkSeq) {
        String key = ReplayObjects.objectKey(tenantId, sessionId, chunkSeq);
        ResponseInputStream<GetObjectResponse> object;
        try {
            object = s3.getObject(b -> b.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ZstdInputStream(object));
        } catch (IOException e) {
            try {
                object.close();
            } catch (IOException ignored) {
                // already failing
            }
            throw new UncheckedIOException(e);
        }
    }
}
