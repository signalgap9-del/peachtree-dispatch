package com.atmospath.platform.saas.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes JDBC connections by the read-only flag of the current transaction:
 * {@code @Transactional(readOnly = true)} goes to the replica, everything
 * else to the primary. Outside a transaction (schema validation, ad hoc
 * lookups) the primary is used so reads never observe stale replicas.
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    public static final String PRIMARY = "primary";
    public static final String REPLICA = "replica";

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? REPLICA : PRIMARY;
    }
}
