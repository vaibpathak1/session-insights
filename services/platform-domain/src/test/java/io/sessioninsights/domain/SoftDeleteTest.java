package io.sessioninsights.domain;

import io.sessioninsights.domain.tenancy.TenantContext;
import io.sessioninsights.domain.tenant.MaskingAction;
import io.sessioninsights.domain.tenant.MaskingRule;
import io.sessioninsights.domain.tenant.MaskingRuleRepository;
import io.sessioninsights.domain.tenant.Site;
import io.sessioninsights.domain.tenant.SiteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SoftDeleteTest extends DomainIntegrationTest {

    @Autowired SiteRepository sites;
    @Autowired MaskingRuleRepository maskingRules;

    @Test
    void softDeletedRowsAreInvisibleToRepositoriesButStayInTheTable() {
        Fixture f = fixture();
        TenantContext.runAs(f.tenantId(), () -> {
            UUID siteId = tx.execute(s -> sites.save(new Site(f.tenantId(), "to-delete", "https://a.example")).getId());
            UUID ruleId = tx.execute(s -> maskingRules.save(
                    new MaskingRule(f.tenantId(), siteId, ".secret", MaskingAction.MASK)).getId());

            tx.executeWithoutResult(s -> {
                maskingRules.deleteById(ruleId);
                sites.deleteById(siteId);
            });

            tx.executeWithoutResult(s -> {
                assertThat(sites.findById(siteId)).isEmpty();
                assertThat(sites.findAll()).extracting(Site::getId).doesNotContain(siteId);
                assertThat(maskingRules.existsById(ruleId)).isFalse();
                assertThat(maskingRules.count()).isZero();
            });
        });

        Map<String, Object> row = owner().sql("SELECT is_active, deleted_at, version FROM site WHERE name = 'to-delete'"
                + " AND tenant_id = ?").param(f.tenantId()).query().singleRow();
        assertThat(row.get("is_active")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(owner().sql("SELECT count(*) FROM masking_rule WHERE tenant_id = ?").param(f.tenantId())
                .query(Long.class).single()).isEqualTo(1L);
    }
}
