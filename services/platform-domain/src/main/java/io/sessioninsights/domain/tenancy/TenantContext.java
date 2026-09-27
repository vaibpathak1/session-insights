package io.sessioninsights.domain.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant the current thread acts for (ADR-0008). Read by
 * {@link TenantAwareJpaTransactionManager} when a transaction begins; with no tenant set,
 * row-level security hides every tenant-scoped row.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Runs {@code action} as {@code tenantId}; transactions must begin inside the action. */
    public static <T> T callAs(UUID tenantId, Supplier<T> action) {
        Objects.requireNonNull(tenantId, "tenantId");
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(UUID tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
