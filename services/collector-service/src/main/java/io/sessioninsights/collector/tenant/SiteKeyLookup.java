package io.sessioninsights.collector.tenant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Calls {@code resolve_site_key()}, the collector role's only database access (ADR-0011). */
@Component
public class SiteKeyLookup {

    private final JdbcClient jdbc;

    public SiteKeyLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ResolvedSite> find(String keyHash) {
        return jdbc.sql("SELECT tenant_id, site_id, allowed_origins, sampling_rate FROM resolve_site_key(?)")
                .param(keyHash)
                .query((rs, row) -> new ResolvedSite(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("site_id", UUID.class),
                        List.of((String[]) rs.getArray("allowed_origins").getArray()),
                        rs.getBigDecimal("sampling_rate")))
                .optional();
    }
}
