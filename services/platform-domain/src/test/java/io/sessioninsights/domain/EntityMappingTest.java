package io.sessioninsights.domain;

import io.sessioninsights.domain.insight.Evidence;
import io.sessioninsights.domain.insight.SessionInsight;
import io.sessioninsights.domain.insight.SessionInsightRepository;
import io.sessioninsights.domain.privacy.ErasureRequest;
import io.sessioninsights.domain.privacy.ErasureRequestRepository;
import io.sessioninsights.domain.privacy.ErasureSubjectType;
import io.sessioninsights.domain.review.ReviewEvent;
import io.sessioninsights.domain.review.ReviewEventRepository;
import io.sessioninsights.domain.session.AnalysisStatus;
import io.sessioninsights.domain.tenant.AppUser;
import io.sessioninsights.domain.tenant.AppUserRepository;
import io.sessioninsights.domain.tenant.AppUserRole;
import io.sessioninsights.domain.tenant.MaskingAction;
import io.sessioninsights.domain.tenant.MaskingRule;
import io.sessioninsights.domain.tenant.MaskingRuleRepository;
import io.sessioninsights.domain.tenant.ModelProvider;
import io.sessioninsights.domain.tenant.ModelProviderConfig;
import io.sessioninsights.domain.tenant.ModelProviderConfigRepository;
import io.sessioninsights.domain.tenant.Site;
import io.sessioninsights.domain.tenant.SiteKey;
import io.sessioninsights.domain.tenant.SiteKeyRepository;
import io.sessioninsights.domain.tenant.SiteKeys;
import io.sessioninsights.domain.tenant.SiteRepository;
import io.sessioninsights.domain.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Context start = ddl-auto=validate passed; each entity round-trips through the app role under RLS. */
class EntityMappingTest extends DomainIntegrationTest {

    @Autowired SiteRepository sites;
    @Autowired SiteKeyRepository siteKeys;
    @Autowired MaskingRuleRepository maskingRules;
    @Autowired ModelProviderConfigRepository modelConfigs;
    @Autowired AppUserRepository appUsers;
    @Autowired SessionInsightRepository insights;
    @Autowired ReviewEventRepository reviewEvents;
    @Autowired ErasureRequestRepository erasures;

    @Test
    void configEntitiesRoundTrip() {
        Fixture f = fixture();
        TenantContext.runAs(f.tenantId(), () -> {
            UUID siteId = tx.execute(s -> {
                Site site = new Site(f.tenantId(), "shop", "http://localhost:*");
                site.setSignalThresholds(Map.of("rageClicks", 3));
                return sites.save(site).getId();
            });
            SiteKeys.IssuedKey key = SiteKeys.issue("test");
            tx.executeWithoutResult(s -> {
                siteKeys.save(new SiteKey(f.tenantId(), siteId, key.prefix(), key.hash()));
                maskingRules.save(new MaskingRule(f.tenantId(), siteId, "#card", MaskingAction.BLOCK));
                modelConfigs.save(new ModelProviderConfig(f.tenantId(), ModelProvider.OLLAMA, "qwen2.5:7b", "nomic-embed-text"));
                appUsers.save(new AppUser(f.tenantId(), "admin@example.test", "Admin", AppUserRole.ADMIN));
            });

            tx.executeWithoutResult(s -> {
                Site site = sites.findById(siteId).orElseThrow();
                assertThat(site.getAllowedOrigins()).containsExactly("http://localhost:*");
                assertThat(site.getSignalThresholds()).containsEntry("rageClicks", 3);
                assertThat(site.getVersion()).isZero();
                assertThat(siteKeys.findAll()).singleElement()
                        .satisfies(k -> assertThat(k.getKeyHash()).isEqualTo(SiteKeys.hash(key.plaintext())));
                assertThat(maskingRules.findAll()).extracting(MaskingRule::getAction).containsExactly(MaskingAction.BLOCK);
                assertThat(modelConfigs.findAll()).extracting(ModelProviderConfig::getProvider).containsExactly(ModelProvider.OLLAMA);
                assertThat(appUsers.findAll()).extracting(AppUser::getRole).containsExactly(AppUserRole.ADMIN);
            });
        });
    }

    @Test
    void insightReviewAndErasureRoundTrip() {
        Fixture f = fixture();
        float[] embedding = new float[SessionInsight.EMBEDDING_DIMENSIONS];
        Arrays.fill(embedding, 0.5f);
        TenantContext.runAs(f.tenantId(), () -> {
            Long insightId = tx.execute(s -> {
                SessionInsight insight = new SessionInsight(f.tenantId(), f.sessionId(), ModelProvider.OLLAMA, "qwen2.5:7b");
                insight.setAnalysis("Payment failed", "card declined", (short) 80, List.of("checkout", "payment"),
                        List.of(new Evidence(12_500, "third click on Pay")));
                insight.setEmbedding(embedding);
                return insights.save(insight).getId();
            });
            tx.executeWithoutResult(s -> {
                reviewEvents.save(new ReviewEvent(f.tenantId(), f.sessionId(), null, "STATUS_CHANGE",
                        AnalysisStatus.AI_ANALYZED, AnalysisStatus.REVIEW_REQUIRED, "guardrail",
                        Map.of("status", "AI_ANALYZED"), Map.of("status", "REVIEW_REQUIRED")));
                erasures.save(new ErasureRequest(f.tenantId(), ErasureSubjectType.SESSION, f.sessionId(), null));
            });

            tx.executeWithoutResult(s -> {
                SessionInsight insight = insights.findById(insightId).orElseThrow();
                assertThat(insight.getAutoTags()).containsExactly("checkout", "payment");
                assertThat(insight.getEvidence()).containsExactly(new Evidence(12_500, "third click on Pay"));
                assertThat(insight.getEmbedding()).hasSize(768).containsOnly(0.5f);
                assertThat(reviewEvents.findBySessionIdOrderByCreatedAtAsc(f.sessionId())).singleElement()
                        .satisfies(e -> assertThat(e.getAfter()).containsEntry("status", "REVIEW_REQUIRED"));
                assertThat(erasures.findAll()).singleElement()
                        .satisfies(e -> assertThat(e.getSubjectId()).isEqualTo(f.sessionId()));
            });
        });
    }
}
