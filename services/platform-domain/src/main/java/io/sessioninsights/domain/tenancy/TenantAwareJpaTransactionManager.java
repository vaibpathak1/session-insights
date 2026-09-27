package io.sessioninsights.domain.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sets {@code app.tenant_id} from {@link TenantContext} at the start of every JPA transaction.
 * The setting is transaction-local ({@code set_config(..., true)}), so a pooled connection
 * never carries a tenant into the next transaction. Without a tenant nothing is set and
 * row-level security returns no tenant-scoped rows (fail closed).
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public TenantAwareJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        TenantContext.current().ifPresent(tenantId -> {
            var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
            holder.getEntityManager()
                    .createNativeQuery("SELECT set_config('app.tenant_id', ?1, true)")
                    .setParameter(1, tenantId.toString())
                    .getSingleResult();
        });
    }
}
