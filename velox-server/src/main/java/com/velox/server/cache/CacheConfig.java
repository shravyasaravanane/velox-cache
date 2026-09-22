package com.velox.server.cache;

import com.velox.core.Cache;
import com.velox.core.CacheBuilder;
import com.velox.core.policy.Policy;
import com.velox.server.domain.ProductDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one cache this application's read and write paths share, built from {@code velox-core}
 * exactly as any other Spring Boot application would use a library cache: through
 * {@link CacheBuilder}'s public API, nothing special.
 *
 * <p>It is a {@link HotSwappableCache}, not a plain {@link Cache}, so {@code
 * POST /api/admin/policy/{name}} and {@code POST /api/admin/capacity/{n}} can rebuild it live --
 * every other bean here still only depends on the {@link Cache} interface, and gets the swap for
 * free through the same reference.
 */
@Configuration
public class CacheConfig {

    /**
     * @param capacity how many {@link ProductDetails} the cache may hold
     * @param policy   any {@link Policy} constant by name, e.g. {@code LRU}, {@code ARC},
     *                 {@code W_TINY_LFU} -- the default is the policy this whole project was
     *                 built to demonstrate
     * @param shards   {@code concurrencyLevel} for the underlying {@code ShardedCache} -- this
     *                 cache is read and written from many servlet threads at once, so it is
     *                 never built without one; see {@link HotSwappableCache}'s Javadoc
     */
    @Bean
    public HotSwappableCache<Long, ProductDetails> productCache(
            @Value("${velox.demo.cache-capacity:10000}") int capacity,
            @Value("${velox.demo.cache-policy:W_TINY_LFU}") Policy policy,
            @Value("${velox.demo.cache-shards:16}") int shards) {
        return new HotSwappableCache<>(capacity, policy, shards);
    }
}
