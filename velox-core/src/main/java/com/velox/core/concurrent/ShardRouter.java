package com.velox.core.concurrent;

import com.velox.core.util.Hashing;

/**
 * Decides which shard a key belongs to.
 *
 * <h2>Why shard at all</h2>
 *
 * One lock around the whole cache serialises every request. Split the cache into N
 * independent shards, each with its own lock, and two requests only wait for each
 * other if their keys land in the same shard: contention drops by roughly a factor
 * of N.
 *
 * <h2>Why the HIGH bits</h2>
 *
 * Each shard's hash table finds a slot with {@code hash & (capacity - 1)}, which
 * uses the <b>low</b> bits. If the router also chose the shard from the low bits,
 * every key in a given shard would agree on those bits, so they would all crowd into
 * the same few slots of that shard's table: the two decisions would be correlated
 * and the table would cluster badly.
 *
 * <p>Taking the shard from the <b>high</b> bits keeps the two independent. Given the
 * shard, the low bits are still uniformly distributed. It is a small detail that
 * would otherwise silently cost hit ratio and speed.
 *
 * <p>The shard count is a power of two so the shard is just a shift of the hash, with
 * no division.
 */
public final class ShardRouter {

    private final int shardCount;
    private final int shift;

    /**
     * @param requestedShards the desired number of shards; rounded up to a power of two
     */
    public ShardRouter(int requestedShards) {
        if (requestedShards < 1) {
            throw new IllegalArgumentException("shards must be at least 1, got " + requestedShards);
        }
        this.shardCount = Hashing.nextPowerOfTwo(requestedShards);
        this.shift = 32 - Integer.numberOfTrailingZeros(shardCount);
    }

    /** @return the number of shards, a power of two */
    public int shardCount() {
        return shardCount;
    }

    /**
     * @param spreadHash a hash already run through {@link Hashing#spread}; raw
     *                   {@code hashCode()} values are too poorly mixed in their high bits
     * @return the shard index in {@code [0, shardCount)}
     * @implNote O(1): one shift
     */
    public int shardFor(int spreadHash) {
        // A shift of 32 would be a no-op in Java (shift distances are taken mod 32), so a
        // single shard needs its own answer rather than relying on the shift.
        return shardCount == 1 ? 0 : spreadHash >>> shift;
    }
}
