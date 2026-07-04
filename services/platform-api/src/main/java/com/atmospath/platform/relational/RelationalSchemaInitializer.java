package com.atmospath.platform.relational;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest;

@Component
@ConditionalOnProperty(name = "atmospath.relational.initialize-schema", havingValue = "true")
public class RelationalSchemaInitializer implements ApplicationRunner {
    private final RdsDataClient client;
    private final RelationalStoreProperties properties;

    public RelationalSchemaInitializer(RdsDataClient client, RelationalStoreProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:db/migration/*.sql");
        Arrays.sort(resources, java.util.Comparator.comparing(Resource::getFilename));
        for (Resource resource : resources) {
            runMigration(resource);
        }
    }

    private void runMigration(Resource resource) throws IOException {
        var sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : sql.split("(?m)^-- statement\\s*$")) {
            if (!statement.isBlank()) {
                client.executeStatement(baseRequest(statement).build());
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
