package io.sessioninsights.api.security;

import io.sessioninsights.api.ApiApplication;
import io.sessioninsights.api.ApiIntegrationTest;
import io.sessioninsights.db.testing.TestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-0013: the API fails closed without real authentication. */
class StartupGuardTest extends ApiIntegrationTest {

    @Test
    void anyProfileOtherThanDevRefusesToStart() {
        for (String profile : new String[] {"prod", "default"}) {
            assertThatThrownBy(() -> start(profile, DEV_PASSWORD).close())
                    .as(profile).hasStackTraceContaining("Refusing to start");
        }
    }

    @Test
    void devRefusesToStartWithoutAStrongEnoughPassword() {
        for (String password : new String[] {"", "short"}) {
            assertThatThrownBy(() -> start("dev", password).close())
                    .hasStackTraceContaining("DEV_ADMIN_PASSWORD must be set");
        }
    }

    private static org.springframework.context.ConfigurableApplicationContext start(String profile, String password) {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        props.put("spring.datasource.username", TestContainers.APP_USER);
        props.put("spring.datasource.password", TestContainers.APP_PASSWORD);
        props.put("spring.flyway.user", POSTGRES.getUsername());
        props.put("spring.flyway.password", POSTGRES.getPassword());
        TestContainers.flywayPlaceholders().forEach((k, v) -> props.put("spring.flyway.placeholders." + k, v));
        props.put("clickhouse.endpoint", TestContainers.clickhouseEndpoint(CLICKHOUSE));
        props.put("clickhouse.database", TestContainers.DATABASE);
        props.put("clickhouse.username", TestContainers.USER);
        props.put("clickhouse.password", TestContainers.PASSWORD);
        props.put("api.dev-auth.password", password);
        props.put("server.port", "0");
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ApiApplication.class)
                .web(WebApplicationType.SERVLET).properties(props);
        if (!"default".equals(profile)) {
            builder.profiles(profile);
        }
        return builder.run();
    }
}
