package io.sessioninsights.domain;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(DomainJpaConfiguration.class)
class DomainTestApplication {
}
