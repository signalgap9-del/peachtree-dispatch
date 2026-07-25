package com.atmospath.platform.saas.config;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Wires the SaaS relational stack only when {@code atmospath.saas.enabled}
 * is true. JDBC/JPA auto-configuration is excluded on the application class,
 * so the non-SaaS app boots exactly as before without any datasource.
 */
@Configuration
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
@EnableConfigurationProperties(SaasDataSourceProperties.class)
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.atmospath.platform.saas.repository",
        entityManagerFactoryRef = "saasEntityManagerFactory",
        transactionManagerRef = "saasTransactionManager")
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean(destroyMethod = "close")
    HikariDataSource saasPrimaryDataSource(SaasDataSourceProperties properties) {
        return hikari("saas-primary", properties.primaryUrl(), properties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${atmospath.datasource.replica-url:}'.isBlank()")
    HikariDataSource saasReplicaDataSource(SaasDataSourceProperties properties) {
        return hikari("saas-replica", properties.replicaUrl(), properties);
    }

    @Bean
    DataSource saasRoutingDataSource(SaasDataSourceProperties properties,
                                     HikariDataSource saasPrimaryDataSource,
                                     @Qualifier("saasReplicaDataSource")
                                     ObjectProvider<HikariDataSource> saasReplicaDataSource) {
        RoutingDataSource routing = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(RoutingDataSource.PRIMARY, saasPrimaryDataSource);
        HikariDataSource replica = saasReplicaDataSource.getIfAvailable();
        if (replica != null) {
            targets.put(RoutingDataSource.REPLICA, replica);
            log.info("SaaS read replica enabled: {}", properties.replicaUrl());
        } else {
            targets.put(RoutingDataSource.REPLICA, saasPrimaryDataSource);
            log.info("No SaaS replica configured; routing reads to the primary");
        }
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(saasPrimaryDataSource);
        routing.afterPropertiesSet();
        return routing;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean saasEntityManagerFactory(DataSource saasRoutingDataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(saasRoutingDataSource);
        factory.setPackagesToScan("com.atmospath.platform.saas.entity");
        factory.setPersistenceUnitName("saas");
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect");
        factory.setJpaVendorAdapter(vendorAdapter);
        Map<String, Object> jpaProperties = new HashMap<>();
        // Schema is owned by the Flyway migrations in db/migration; Hibernate
        // validates entity mappings against it at startup.
        jpaProperties.put("hibernate.hbm2ddl.auto", "validate");
        jpaProperties.put("hibernate.jdbc.time_zone", "UTC");
        factory.setJpaPropertyMap(jpaProperties);
        return factory;
    }

    @Bean
    PlatformTransactionManager saasTransactionManager(EntityManagerFactory saasEntityManagerFactory) {
        return new JpaTransactionManager(saasEntityManagerFactory);
    }

    private static HikariDataSource hikari(String poolName, String jdbcUrl, SaasDataSourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(properties.maxPoolSize());
        config.setConnectionTimeout(properties.connectionTimeoutMs());
        config.setReadOnly(false);
        return new HikariDataSource(config);
    }
}
