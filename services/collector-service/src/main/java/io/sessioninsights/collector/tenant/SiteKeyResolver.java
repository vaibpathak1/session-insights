package io.sessioninsights.collector.tenant;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.sessioninsights.collector.config.CollectorProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Site key → tenant/site, cached in process (ADR-0010). Known keys are cached for the
 * positive TTL, unknown or revoked keys for the shorter negative TTL, so random keys cannot
 * hammer the database. Concurrent misses for one key share a single lookup.
 * <p>
 * The cache is async on purpose: a synchronous Caffeine loader runs inside a
 * {@code ConcurrentHashMap} bin lock ({@code synchronized}), and blocking JDBC there would
 * pin the virtual thread. Here the lookup runs on its own virtual thread and callers wait
 * on the future outside any lock.
 */
@Component
public class SiteKeyResolver implements DisposableBean {

    private final SiteKeyLookup lookup;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AsyncCache<String, Optional<ResolvedSite>> cache;

    public SiteKeyResolver(SiteKeyLookup lookup, CollectorProperties properties, Ticker ticker) {
        this.lookup = lookup;
        Duration positiveTtl = properties.keyCache().positiveTtl();
        Duration negativeTtl = properties.keyCache().negativeTtl();
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.keyCache().maximumSize())
                .ticker(ticker)
                .executor(executor)
                .expireAfter(Expiry.<String, Optional<ResolvedSite>>writing(
                        (key, site) -> site.isPresent() ? positiveTtl : negativeTtl))
                .buildAsync();
    }

    /**
     * Resolves a site key by its {@link SiteKeyHash}; empty if unknown, revoked, or its site
     * or tenant is inactive. Database failures propagate and are not cached.
     */
    public Optional<ResolvedSite> resolve(String keyHash) {
        try {
            return cache.get(keyHash, (key, exec) -> CompletableFuture.supplyAsync(() -> lookup.find(key), exec)).join();
        } catch (CompletionException e) {
            // failed lookups are not cached; rethrow the cause (e.g. DataAccessException)
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Override
    public void destroy() {
        executor.close();
    }
}
