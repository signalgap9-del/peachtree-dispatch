package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest;

class RelationalSchemaInitializerTests {
    @Test
    void runsVersionedMigrationsInFilenameOrder() throws Exception {
        var client = mock(RdsDataClient.class);
        var properties = new RelationalStoreProperties(true, true, "atmospath", "cluster-arn", "secret-arn");
        var initializer = new RelationalSchemaInitializer(client, properties);

        initializer.run(new DefaultApplicationArguments());

        var captor = ArgumentCaptor.forClass(ExecuteStatementRequest.class);
        verify(client, org.mockito.Mockito.atLeast(2)).executeStatement(captor.capture());
        List<String> sql = captor.getAllValues().stream().map(ExecuteStatementRequest::sql).toList();
        assertThat(sql.getFirst()).contains("CREATE EXTENSION IF NOT EXISTS postgis");
        assertThat(sql).anySatisfy(statement -> assertThat(statement).contains("CREATE TABLE IF NOT EXISTS route_observation"));
    }
}
