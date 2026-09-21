package com.velox.core;

/**
 * Told whenever a value leaves the cache, and why.
 *
 * <p>Typical uses: closing a resource held by the value (a file handle, a
 * connection), writing a dirty entry back to storage, or feeding a dashboard the
 * stream of evictions.
 *
 * <h2>What gets reported</h2>
 *
 * <table>
 *   <caption>Removal causes</caption>
 *   <tr><th>cause</th><th>when</th><th>value reported</th></tr>
 *   <tr><td>{@link RemovalCause#EXPLICIT}</td><td>{@code invalidate}, {@code invalidateAll}</td><td>the removed value</td></tr>
 *   <tr><td>{@link RemovalCause#REPLACED}</td><td>{@code put} over an existing key</td><td>the <b>old</b> value</td></tr>
 *   <tr><td>{@link RemovalCause#SIZE}</td><td>evicted to make room, <b>or refused on the way in</b></td><td>the evicted or refused value</td></tr>
 *   <tr><td>{@link RemovalCause#EXPIRED}</td><td>its time-to-live ran out</td><td>the expired value</td></tr>
 * </table>
 *
 * <h2>The guarantee: nothing is lost without a word</h2>
 *
 * Every value handed to the cache is, at any moment, <b>either still in the cache
 * or has been reported here exactly once</b>. That includes values the cache
 * refuses: a value too heavy for the whole cache, or turned away by an admission
 * policy, was never stored, but it is still reported (as {@code SIZE}) because the
 * cache took it and did not keep it. Without that, a listener that closes
 * resources would leak precisely the ones the cache declined.
 *
 * <p>The one exception: replacing a value with the <i>same object</i>
 * ({@code put(k, v)} then {@code put(k, v)} again) reports nothing, because nothing
 * left.
 *
 * <h2>When and where it runs</h2>
 *
 * <ul>
 *   <li><b>On the thread that caused the removal</b>, synchronously. A slow listener
 *       slows that call; keep it quick, or hand the work to your own executor.</li>
 *   <li><b>Only after the operation has finished and the cache is consistent.</b>
 *       The cache queues notifications and delivers them at the end of the public
 *       call, never halfway through an eviction. So a listener can safely query the
 *       cache: it sees the entry that was just inserted, and the entry that was just
 *       removed is already gone. (In Tier 2 this also means listeners run after
 *       any shard lock is released, so user code never runs under the cache's locks.)</li>
 *   <li><b>In order</b>, one at a time.</li>
 * </ul>
 *
 * <h2>What a listener may do</h2>
 *
 * <ul>
 *   <li><b>Call back into the cache.</b> A {@code put} from inside a listener is
 *       fine (say, to refresh an expired entry). Notifications it triggers are
 *       queued and delivered <i>after</i> the current one returns, in order, never
 *       nested inside it. (A listener that unconditionally re-inserts whatever was
 *       evicted from a full cache will, of course, ping-pong forever.)</li>
 *   <li><b>Throw.</b> A {@link RuntimeException} is logged and otherwise ignored:
 *       the caller's {@code put} still succeeds, the cache stays consistent, and the
 *       remaining notifications are still delivered. An {@link Error} propagates to
 *       the caller, but the cache is left consistent and undelivered notifications
 *       stay queued for the next operation.</li>
 * </ul>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
@FunctionalInterface
public interface RemovalListener<K, V> {

    /**
     * @param key   the removed entry's key
     * @param value the removed value; never {@code null}
     * @param cause why it was removed
     */
    void onRemoval(K key, V value, RemovalCause cause);
}
