package com.velox.core.durability;

/** One decoded write-ahead log entry -- what {@link WriteAheadLog} appends, and what
 * {@link WalReader} hands back on replay. A sealed hierarchy so {@link RecoveryManager}'s
 * {@code switch} over the three kinds is exhaustive and checked at compile time: adding a
 * fourth operation later would fail to compile everywhere a switch forgot it, not fail silently
 * at 3 a.m. during a real recovery. */
public sealed interface WalRecord {

    long timestampMillis();

    /** @param ttlMillis how long this entry may live from {@code timestampMillis}, or a
     *                    negative value for no expiry */
    record Put(String key, String value, long ttlMillis, long timestampMillis) implements WalRecord {
    }

    record Invalidate(String key, long timestampMillis) implements WalRecord {
    }

    record Clear(long timestampMillis) implements WalRecord {
    }
}
