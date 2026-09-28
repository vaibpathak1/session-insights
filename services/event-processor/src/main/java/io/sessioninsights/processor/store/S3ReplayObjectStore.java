package io.sessioninsights.processor.store;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.UUID;

/**
 * {@link ReplayObjectStore} over the S3 API (AWS SDK v2, path-style for SeaweedFS). Every
 * failure, including 4xx such as a missing bucket or denied access, is an outage: the chunk
 * itself cannot be the cause, so retrying is the only way not to lose it (ADR-0011).
 */
public class S3ReplayObjectStore implements ReplayObjectStore {

    /**
     * The object is a zstd frame of JSON, typed as such. Deliberately no {@code Content-Encoding:
     * zstd}: HTTP clients would then decompress transparently, or fail where zstd is unsupported
     * (e.g. Safari); the reader decompresses explicitly, as the {@code .json.zst} key says.
     */
    private static final String CONTENT_TYPE = "application/zstd";

    private final S3Client s3;
    private final String bucket;

    public S3ReplayObjectStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(UUID tenantId, UUID sessionId, int chunkSeq, byte[] zstdJson) {
        String key = ReplayObjectStore.objectKey(tenantId, sessionId, chunkSeq);
        try {
            s3.putObject(b -> b.bucket(bucket).key(key).contentType(CONTENT_TYPE),
                    RequestBody.fromBytes(zstdJson));
        } catch (RuntimeException e) {
            throw new StoreUnavailableException(Store.S3, describe(e));
        }
    }

    @Override
    public byte[] get(UUID tenantId, UUID sessionId, int chunkSeq) {
        String key = ReplayObjectStore.objectKey(tenantId, sessionId, chunkSeq);
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key));
            return bytes.asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    private static String describe(RuntimeException e) {
        if (e instanceof software.amazon.awssdk.services.s3.model.S3Exception s3e) {
            return "S3Exception status=" + s3e.statusCode();
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + "/" + root.getClass().getSimpleName();
    }
}
