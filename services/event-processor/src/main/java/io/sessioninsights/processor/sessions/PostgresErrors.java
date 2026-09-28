package io.sessioninsights.processor.sessions;

import java.sql.SQLException;
import java.util.Set;

/**
 * PostgreSQL failures split like ClickHouse's (ADR-0011): only SQLSTATEs about the written
 * values count as data errors; connection, resource, lock and timeout problems, and anything
 * unknown, are outages.
 */
final class PostgresErrors {

    /**
     * Class 22 (data exception, e.g. 22021 a NUL byte in text), class 23 (integrity
     * constraint, e.g. 23503 a site that no longer exists), and 42501 (a row-level security
     * WITH CHECK/USING violation: a session id that already belongs to another tenant).
     */
    static boolean isDataError(SQLException e) {
        String state = e.getSQLState();
        return state != null && (state.startsWith("22") || state.startsWith("23") || DATA_STATES.contains(state));
    }

    private static final Set<String> DATA_STATES = Set.of("42501");

    static SQLException sqlException(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    /** Exception class and SQLSTATE only: PostgreSQL messages can quote the rejected values. */
    static String describe(Throwable failure) {
        SQLException sql = sqlException(failure);
        if (sql != null) {
            return sql.getClass().getSimpleName() + " SQLState=" + sql.getSQLState();
        }
        return failure.getClass().getSimpleName();
    }

    private PostgresErrors() {
    }
}
