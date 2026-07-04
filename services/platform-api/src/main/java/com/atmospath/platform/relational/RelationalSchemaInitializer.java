package com.atmospath.platform.relational;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest;

@Component
@ConditionalOnProperty(name = "atmospath.relational.initialize-schema", havingValue = "true")
public class RelationalSchemaInitializer implements ApplicationRunner {
    private static final List<String> MIGRATIONS = List.of(
            "db/migration/V001__identity_saved_items_postgis.sql",
            "db/migration/V002__tenant_rls_policies.sql");

    private final RdsDataClient client;
    private final RelationalStoreProperties properties;

    public RelationalSchemaInitializer(RdsDataClient client, RelationalStoreProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        for (String migration : MIGRATIONS) {
            var resource = new ClassPathResource(migration);
            var sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String statement : sql.split("(?m)^-- statement\\s*$")) {
                if (!statement.isBlank()) {
                    client.executeStatement(baseRequest(statement).build());
                }
            }
        }
    }

    private ExecuteStatementRequest.Builder baseRequest(String sql) {
        return ExecuteStatementRequest.builder()
                .database(properties.database())
                .resourceArn(properties.resourceArn())
                .secretArn(properties.secretArn())
                .sql(sql.strip());
    }
}
