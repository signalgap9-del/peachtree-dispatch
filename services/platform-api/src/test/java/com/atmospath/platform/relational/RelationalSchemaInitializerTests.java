package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementResponse;

class RelationalSchemaInitializerTests {
    private final RdsDataClient client = mock(RdsDataClient.class);
    private final RelationalStoreProperties properties =
            new RelationalStoreProperties(true, true, "atmospath", "cluster-arn", "secret-arn");

    @Test
    void runsPostgisSchemaAndRlsMigrations() throws Exception {
        when(client.executeStatement(any(ExecuteStatementRequest.class)))
                .thenReturn(ExecuteStatementResponse.builder().build());

        new RelationalSchemaInitializer(client, properties).run(mock(ApplicationArguments.class));

        var captor = ArgumentCaptor.forClass(ExecuteStatementRequest.class);
        verify(client, atLeast(20)).executeStatement(captor.capture());
        var sqlStatements = captor.getAllValues().stream().map(ExecuteStatementRequest::sql).toList();
        assertThat(sqlStatements).anySatisfy(sql -> assertThat(sql).contains("CREATE EXTENSION IF NOT EXISTS postgis"));
        assertThat(sqlStatements).anySatisfy(sql -> assertThat(sql).contains("CREATE OR REPLACE FUNCTION app.current_user_id"));
        assertThat(sqlStatements).anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE saved_item FORCE ROW LEVEL SECURITY"));
        assertThat(sqlStatements).anySatisfy(sql -> assertThat(sql).contains("CREATE POLICY risk_exposure_owner_access"));
    }
}
