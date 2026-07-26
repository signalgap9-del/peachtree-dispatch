package com.atmospath.platform.saas.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tests for {@link RoutingDataSource} lookup-key logic and
 * {@link DataSourceConfig#saasRoutingDataSource} wiring.
 */
class DataSourceConfigTests {

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void routingDataSourceReturnsPrimaryOutsideTransaction() {
        RoutingDataSource routing = new RoutingDataSource();
        Object key = routing.determineCurrentLookupKey();
        assertThat(key).isEqualTo(RoutingDataSource.PRIMARY);
    }

    @Test
    void routingDataSourceReturnsReplicaInReadOnlyTransaction() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        RoutingDataSource routing = new RoutingDataSource();
        Object key = routing.determineCurrentLookupKey();
        assertThat(key).isEqualTo(RoutingDataSource.REPLICA);
    }

    @Test
    void routingDataSourceReturnsPrimaryInWriteTransaction() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        RoutingDataSource routing = new RoutingDataSource();
        Object key = routing.determineCurrentLookupKey();
        assertThat(key).isEqualTo(RoutingDataSource.PRIMARY);
    }

    @Test
    void saasRoutingDataSourceWiresReplicaWhenAvailable() {
        DataSourceConfig config = new DataSourceConfig();
        SaasDataSourceProperties props = new SaasDataSourceProperties(
                "jdbc:postgresql://primary:5432/db", "jdbc:postgresql://replica:5432/db",
                "user", "pass", 5, 3000L);

        HikariDataSource primary = mock(HikariDataSource.class);
        HikariDataSource replica = mock(HikariDataSource.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<HikariDataSource> replicaProvider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(replicaProvider.getIfAvailable()).thenReturn(replica);

        DataSource result = config.saasRoutingDataSource(props, primary, replicaProvider);

        assertThat(result).isInstanceOf(RoutingDataSource.class);
    }

    @Test
    void saasRoutingDataSourceFallsBackToPrimaryWhenNoReplica() {
        DataSourceConfig config = new DataSourceConfig();
        SaasDataSourceProperties props = new SaasDataSourceProperties(
                "jdbc:postgresql://primary:5432/db", "",
                "user", "pass", 5, 3000L);

        HikariDataSource primary = mock(HikariDataSource.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<HikariDataSource> replicaProvider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(replicaProvider.getIfAvailable()).thenReturn(null);

        DataSource result = config.saasRoutingDataSource(props, primary, replicaProvider);

        assertThat(result).isInstanceOf(RoutingDataSource.class);
    }

    @Test
    void routingConstantsAreStable() {
        assertThat(RoutingDataSource.PRIMARY).isEqualTo("primary");
        assertThat(RoutingDataSource.REPLICA).isEqualTo("replica");
    }

    @Test
    void saasDataSourcePropertiesDefaults() {
        SaasDataSourceProperties props = new SaasDataSourceProperties(
                null, null, null, null, 0, 0);

        assertThat(props.primaryUrl()).contains("localhost");
        assertThat(props.username()).isEqualTo("atmospath");
        assertThat(props.maxPoolSize()).isEqualTo(20);
        assertThat(props.connectionTimeoutMs()).isEqualTo(5000L);
        assertThat(props.hasReplica()).isFalse();
    }

    @Test
    void saasDataSourcePropertiesHasReplica() {
        SaasDataSourceProperties props = new SaasDataSourceProperties(
                "jdbc:postgresql://primary/db", "jdbc:postgresql://replica/db",
                "user", "pass", 10, 5000L);

        assertThat(props.hasReplica()).isTrue();
    }
}
