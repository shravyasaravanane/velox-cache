package com.velox.server.livestats;

import com.velox.core.sketch.HyperLogLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two observability structures behind the dashboard's hot-keys panel (M6.5).
 * {@link HyperLogLog} lives in {@code velox-core} as a general-purpose library structure, so it
 * needs a {@code @Bean} factory method here rather than a {@code @Component} annotation on the
 * library class itself; {@link TopKTracker} is server-specific and could have been annotated
 * directly, but is built here too so both beans' configuration lives in one place.
 */
@Configuration
public class LiveStatsConfig {

    /** @param precision see {@link HyperLogLog#HyperLogLog(int)}; 14 is ~0.8% standard error at 16 KB */
    @Bean
    public HyperLogLog cardinalityEstimator(@Value("${velox.demo.hll-precision:14}") int precision) {
        return new HyperLogLog(precision);
    }

    /** @param k how many hot keys the Space-Saving tracker follows */
    @Bean
    public TopKTracker topKTracker(@Value("${velox.demo.top-k:10}") int k) {
        return new TopKTracker(k);
    }
}
