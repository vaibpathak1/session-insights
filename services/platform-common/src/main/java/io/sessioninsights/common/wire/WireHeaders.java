package io.sessioninsights.common.wire;

/** Kafka record headers and the envelope schema version (ADR-0004). */
public final class WireHeaders {

    public static final String TENANT_ID = "si-tenant-id";
    public static final String SCHEMA_VERSION = "si-schema-version";

    /** Version of the envelopes in this package; bump on incompatible change. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private WireHeaders() {
    }
}
