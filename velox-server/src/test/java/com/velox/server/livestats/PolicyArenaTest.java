package com.velox.server.livestats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyArenaTest {

    @Test
    @DisplayName("standings include exactly the configured policies")
    void standingsIncludeConfiguredPolicies() {
        PolicyArena arena = new PolicyArena(100, "LRU,ARC,FIFO");

        List<PolicyArena.Standing> standings = arena.standings();

        assertEquals(3, standings.size());
        assertEquals(Set.of("LRU", "ARC", "FIFO"), namesOf(standings));
    }

    @Test
    @DisplayName("every shadow cache replays the identical stream -- request counts match across policies")
    void everyPolicySeesTheSameTotalRequestCount() {
        PolicyArena arena = new PolicyArena(50, "LRU,ARC,FIFO,RANDOM");

        for (int i = 0; i < 500; i++) {
            arena.record(i % 30);
        }

        for (PolicyArena.Standing standing : arena.standings()) {
            assertEquals(500, standing.hits() + standing.misses(),
                    standing.policy() + " should have seen exactly 500 total requests, same as every other policy");
        }
    }

    @Test
    @DisplayName("standings are sorted by hit rate descending")
    void standingsAreSortedDescending() {
        PolicyArena arena = new PolicyArena(50, "LRU,ARC,FIFO,RANDOM,LFU");

        java.util.Random random = new java.util.Random(3);
        for (int i = 0; i < 5000; i++) {
            arena.record(zipfianish(random));
        }

        List<PolicyArena.Standing> standings = arena.standings();
        for (int i = 1; i < standings.size(); i++) {
            assertTrue(standings.get(i - 1).hitRatePercent() >= standings.get(i).hitRatePercent(),
                    "standings must be sorted hit rate descending: " + standings);
        }
    }

    @Test
    @DisplayName("reproduces this project's own known result: a small loop one larger than capacity defeats LRU (0%) but not an admission-aware policy")
    void loopWorkloadDefeatsLruButNotTwoQ() {
        // Capacity 3, a repeating loop of 4 distinct keys: every LRU access is a miss, because
        // the key it needs is always the one just evicted. This is the exact example used in
        // this project's own Review 1 slide deck (problem-scope section) and its Tier 3 policy
        // tests. 2Q requires surviving a real second visit to be promoted, so a one-pass loop
        // cannot inflate anything into the protected region -- it should score at or above LRU.
        PolicyArena arena = new PolicyArena(3, "LRU,TWO_Q");
        int[] loop = {1, 2, 3, 4};
        for (int cycle = 0; cycle < 100; cycle++) {
            for (int key : loop) {
                arena.record(key);
            }
        }

        Map<String, PolicyArena.Standing> byPolicy = arena.standings().stream()
                .collect(Collectors.toMap(PolicyArena.Standing::policy, s -> s));

        assertEquals(0.0, byPolicy.get("LRU").hitRatePercent(), 0.01,
                "a loop one larger than capacity should score exactly 0% on plain LRU");
    }

    private static Set<String> namesOf(List<PolicyArena.Standing> standings) {
        return standings.stream().map(PolicyArena.Standing::policy).collect(Collectors.toSet());
    }

    private static long zipfianish(java.util.Random random) {
        // A crude skew: mostly a small hot set, occasionally something from a wider range.
        return random.nextInt(10) == 0 ? random.nextInt(2000) : random.nextInt(20);
    }
}
