package com.velox.server.cache;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An inverted index, {@code tag -> the cache keys currently filed under it}, so
 * {@code invalidateByTag} can drop every product in a category (say) in O(m) -- exactly the
 * affected keys -- instead of scanning the whole cache.
 *
 * <p>Entries are added on every cache population and never proactively removed when a key is
 * merely evicted by the cache's own policy (that would need the cache to call back into this
 * index on every eviction, for a benefit that does not matter here: a tag pointing at a key
 * the cache no longer holds is harmless, since {@code Cache.invalidate} on an absent key is a
 * no-op). The index can only grow relative to the cache's own size, which is bounded by how
 * many distinct products have ever been read.
 */
@Component
public class TagIndex {

    private final Map<String, Set<Long>> tagToKeys = new ConcurrentHashMap<>();

    /** Files {@code key} under {@code tag}. */
    public void index(long key, String tag) {
        tagToKeys.computeIfAbsent(tag, unused -> ConcurrentHashMap.newKeySet()).add(key);
    }

    /** @return every key currently filed under {@code tag}; empty if the tag is unknown */
    public Set<Long> keysForTag(String tag) {
        return tagToKeys.getOrDefault(tag, Set.of());
    }
}
