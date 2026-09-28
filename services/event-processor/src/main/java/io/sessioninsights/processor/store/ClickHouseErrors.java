package io.sessioninsights.processor.store;

import com.clickhouse.client.api.ServerException;

import java.util.Set;

/**
 * Splits ClickHouse insert failures into "the data is bad" and "the store is not available"
 * (ADR-0011). Only server errors about the inserted values count as data errors; everything
 * else (connection, timeout, overload, missing table, auth, unknown codes) is treated as an
 * outage, because retrying loses nothing while dead-lettering good data would.
 */
final class ClickHouseErrors {

    /** ClickHouse error codes raised while parsing or converting inserted values. */
    static final Set<Integer> DATA_ERROR_CODES = Set.of(
            6,    // CANNOT_PARSE_TEXT
            26,   // CANNOT_PARSE_QUOTED_STRING
            27,   // CANNOT_PARSE_INPUT_ASSERTION_FAILED
            38,   // CANNOT_PARSE_DATE
            41,   // CANNOT_PARSE_DATETIME
            53,   // TYPE_MISMATCH
            69,   // ARGUMENT_OUT_OF_BOUND
            70,   // CANNOT_CONVERT_TYPE
            72,   // CANNOT_PARSE_NUMBER
            117,  // INCORRECT_DATA (e.g. duplicate paths in a JSON column)
            131,  // TOO_LARGE_STRING_SIZE
            321); // VALUE_IS_OUT_OF_RANGE_OF_DATA_TYPE

    private ClickHouseErrors() {
    }

    /** The server exception in the cause chain, or null for client-side/transport failures. */
    static ServerException serverException(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ServerException server) {
                return server;
            }
        }
        return null;
    }

    static boolean isDataError(ServerException server) {
        return server != null && DATA_ERROR_CODES.contains(server.getCode());
    }

    /** Exception class and error code only; never the message (it can quote row data). */
    static String describe(Throwable failure) {
        ServerException server = serverException(failure);
        if (server != null) {
            return "ServerException code=" + server.getCode();
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == failure ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + "/" + root.getClass().getSimpleName();
    }
}
