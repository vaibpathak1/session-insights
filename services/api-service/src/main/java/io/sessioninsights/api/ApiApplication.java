package io.sessioninsights.api;

import io.sessioninsights.domain.DomainJpaConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Dashboard + HITL review REST API, secured with OIDC. */
@SpringBootApplication
@Import(DomainJpaConfiguration.class)
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
