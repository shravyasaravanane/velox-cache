package com.velox.core.concurrent;

import com.velox.core.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardRouterTest {

    @Test
    @DisplayName("the shard count is rounded up to a power of two")
    void roundsUp() {
        assertEquals(1, new ShardRouter(1).shardCount());
        assertEquals(4, new ShardRouter(3).shardCount());
        assertEquals(16, new ShardRouter(16).shardCount());
        assertEquals(32, new ShardRouter(17).shardCount());
        assertThrows(IllegalArgumentException.class, () -> new ShardRouter(0));
        assertThrows(IllegalArgumentException.class, () -> new ShardRouter(-4));
    }

    @Test
    @DisplayName("a single shard receives everything")
    void singleShard() {
        var router = new ShardRouter(1);

        for (int hash : new int[]{0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 0xDEADBEEF}) {
            assertEquals(0, router.shardFor(hash));
        }
    }

    @Test
    @DisplayName("every hash maps into range, including negative ones")
    void alwaysInRange() {
        for (int shards : new int[]{2, 4, 16, 64, 256}) {
            var router = new ShardRouter(shards);
            for (int i = 0; i < 20_000; i++) {
                int hash = Hashing.mix(i - 10_000);          // covers negative inputs too
                int shard = router.shardFor(hash);
                assertTrue(shard >= 0 && shard < shards, "shard " + shard + " out of range for " + shards);
            }
        }
    }

    @Test
    @DisplayName("sequential keys spread evenly across shards")
    void spreadsSequentialKeys() {
        int shards = 16;
        var router = new ShardRouter(shards);
        int[] counts = new int[shards];
        int keys = 160_000;

        for (int i = 0; i < keys; i++) {
            counts[router.shardFor(Hashing.spread(i))]++;
        }

        int expected = keys / shards;
        for (int shard = 0; shard < shards; shard++) {
            assertTrue(Math.abs(counts[shard] - expected) < expected * 0.05,
                    "shard " + shard + " got " + counts[shard] + ", expected about " + expected);
        }
    }

    @Test
    @DisplayName("routing by the HIGH bits keeps the low bits uniform inside each shard")
    void highBitsKeepTheTableBitsIndependent() {
        // Each shard's hash table picks a slot from the LOW bits of the hash. If the
        // router used those same bits to pick the shard, every key in one shard would
        // agree on them and pile into the same few slots. Taking the shard from the
        // HIGH bits leaves the low bits uniform.
        int shards = 64;
        var router = new ShardRouter(shards);
        int tableSlots = 16;
        int[] slotUsage = new int[tableSlots];
        int inShardZero = 0;

        for (int i = 0; i < 2_000_000; i++) {
            int hash = Hashing.spread(i);
            if (router.shardFor(hash) == 0) {
                slotUsage[hash & (tableSlots - 1)]++;
                inShardZero++;
            }
        }

        double mean = (double) inShardZero / tableSlots;
        for (int slot = 0; slot < tableSlots; slot++) {
            assertTrue(Math.abs(slotUsage[slot] - mean) < mean * 0.25,
                    "slot " + slot + " of shard 0's table got " + slotUsage[slot] + ", mean " + mean);
        }
    }

    @Test
    @DisplayName("...whereas routing by the LOW bits would leave each shard's table using one slot")
    void lowBitRoutingWouldClusterBadly() {
        // The mistake the router avoids, demonstrated. Keys sent to shard 0 by their low
        // 6 bits all have hash & 63 == 0, so they all share the low 4 bits too: every
        // one of them wants the SAME slot in that shard's table.
        int shards = 64;
        int tableSlots = 16;
        int[] slotUsage = new int[tableSlots];

        for (int i = 0; i < 2_000_000; i++) {
            int hash = Hashing.spread(i);
            if ((hash & (shards - 1)) == 0) {
                slotUsage[hash & (tableSlots - 1)]++;
            }
        }

        int usedSlots = 0;
        for (int usage : slotUsage) {
            if (usage > 0) {
                usedSlots++;
            }
        }
        assertEquals(1, usedSlots, "low-bit routing collapses the shard's table onto a single slot");
    }
}
