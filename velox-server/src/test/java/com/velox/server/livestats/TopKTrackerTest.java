package com.velox.server.livestats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopKTrackerTest {

    @Test
    @DisplayName("capacity must be at least 1")
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TopKTracker(0));
    }

    @Test
    @DisplayName("with fewer distinct keys than capacity, every count is exact")
    void exactCountsWhenUnderCapacity() {
        TopKTracker tracker = new TopKTracker(10);
        for (int i = 0; i < 5; i++) {
            tracker.record(1);
        }
        for (int i = 0; i < 3; i++) {
            tracker.record(2);
        }
        tracker.record(3);

        List<TopKTracker.Entry> top = tracker.topK();
        assertEquals(3, top.size());
        assertEquals(new TopKTracker.Entry(1, 5), top.get(0));
        assertEquals(new TopKTracker.Entry(2, 3), top.get(1));
        assertEquals(new TopKTracker.Entry(3, 1), top.get(2));
    }

    @Test
    @DisplayName("a genuinely hot key is never displaced by a flood of one-off noise keys, when its count clears the proven error bound")
    void hotKeySurvivesNoiseFlood() {
        // The Space-Saving guarantee is trueCount + N/k, not "the biggest true count always
        // wins" -- a one-off noise key's estimated count can be inflated to as much as N/k by
        // the time it is recorded. For the hot key to be provably safe from eviction, its true
        // count must clear that bound with margin: capacity=10, ~6,000 total records gives
        // N/k ~= 600, well under the hot key's 2,000 hits.
        int k = 10;
        TopKTracker tracker = new TopKTracker(k);
        int hotKeyHits = 2000;

        for (int i = 0; i < hotKeyHits; i++) {
            tracker.record(999); // the one genuinely hot key
        }
        Random random = new Random(1);
        int noiseCount = 5000;
        for (int i = 0; i < noiseCount; i++) {
            tracker.record(random.nextLong()); // distinct one-off keys, essentially never repeated
        }

        double bound = (double) (hotKeyHits + noiseCount) / k;
        assertTrue(bound < hotKeyHits, "test setup must keep the error bound (%.0f) under the hot key's true count".formatted(bound));

        List<TopKTracker.Entry> top = tracker.topK();
        assertEquals(999L, top.get(0).key(), "the hot key must still be #1: no noise key's bounded overestimate can clear " + hotKeyHits);
        assertTrue(top.get(0).count() >= hotKeyHits, "the hot key's count can only be an overestimate, never an undercount");
    }

    @Test
    @DisplayName("every tracked count is within the proven Space-Saving error bound of the truth (trueCount + N/k)")
    void countsRespectTheSpaceSavingErrorBound() {
        int k = 20;
        TopKTracker tracker = new TopKTracker(k);
        java.util.Map<Long, Long> trueCounts = new java.util.HashMap<>();
        Random random = new Random(42);
        long streamLength = 0;

        // A Zipfian-ish stream: a handful of hot keys plus a long noisy tail, both real
        // repeats and one-offs -- exercises both branches of record() (new tracked key vs.
        // already-tracked key) many times over.
        for (int i = 0; i < 100_000; i++) {
            long key = random.nextInt(10) == 0 ? random.nextInt(3) : 1000 + random.nextInt(20_000);
            tracker.record(key);
            trueCounts.merge(key, 1L, Long::sum);
            streamLength++;
        }

        double bound = (double) streamLength / k;
        for (TopKTracker.Entry entry : tracker.topK()) {
            long trueCount = trueCounts.getOrDefault(entry.key(), 0L);
            assertTrue(entry.count() >= trueCount, "tracked count must never undercount the truth");
            assertTrue(entry.count() <= trueCount + bound,
                    "tracked count %d for key %d exceeds trueCount(%d) + N/k(%.1f)"
                            .formatted(entry.count(), entry.key(), trueCount, bound));
        }
    }

    @Test
    @DisplayName("topK is sorted by count descending")
    void topKIsSortedDescending() {
        TopKTracker tracker = new TopKTracker(4);
        tracker.record(1);
        for (int i = 0; i < 3; i++) tracker.record(2);
        for (int i = 0; i < 5; i++) tracker.record(3);
        for (int i = 0; i < 2; i++) tracker.record(4);

        List<Long> counts = tracker.topK().stream().map(TopKTracker.Entry::count).collect(Collectors.toList());
        assertEquals(List.of(5L, 3L, 2L, 1L), counts);
    }
}
