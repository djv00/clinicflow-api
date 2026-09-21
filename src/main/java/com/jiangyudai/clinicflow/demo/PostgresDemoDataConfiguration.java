package com.jiangyudai.clinicflow.demo;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("postgres")
@ConditionalOnProperty(prefix = "clinicflow.demo-data", name = "enabled", havingValue = "true")
public class PostgresDemoDataConfiguration {

    @Bean
    ApplicationRunner initializeDemoLocations(DataSource dataSource, PlatformTransactionManager transactionManager) {
        var locations = new ResourceDatabasePopulator(new ClassPathResource("demo/postgresql-locations.sql"));
        var transaction = new TransactionTemplate(transactionManager);
        // A conflicting reference must not leave a partially initialized demo dictionary.
        return arguments -> transaction.executeWithoutResult(status -> locations.execute(dataSource));
    }
}
