package io.sessioninsights.domain;

import io.sessioninsights.domain.tenancy.TenantAwareJpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;

/** Import from an application to use the domain layer; replaces Boot's default JPA transaction manager. */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = DomainJpaConfiguration.class)
@EnableJpaRepositories(basePackageClasses = DomainJpaConfiguration.class)
public class DomainJpaConfiguration {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new TenantAwareJpaTransactionManager(entityManagerFactory);
    }
}
