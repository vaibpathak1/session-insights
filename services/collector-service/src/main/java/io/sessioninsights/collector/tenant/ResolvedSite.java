package io.sessioninsights.collector.tenant;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Server-side identity for a site key, as returned by {@code resolve_site_key()}. */
public record ResolvedSite(UUID tenantId, UUID siteId, List<String> allowedOrigins, BigDecimal samplingRate) {
}
