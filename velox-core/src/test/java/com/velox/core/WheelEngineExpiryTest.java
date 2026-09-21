package com.velox.core;

import com.velox.core.expiry.ExpiryEngine;
import com.velox.core.expiry.WheelExpiryEngine;

/**
 * Runs the full expiry suite against the timing wheel with the default
 * geometry: 1 ms ticks, 64 slots per level.
 *
 * <p>The suite's TTLs are seconds and its differential test uses microsecond
 * deadlines, so many deadlines land inside a single 1 ms tick. That exercises
 * the wheel's exact in-tick scanning, not just its coarse slots.
 */
class WheelEngineExpiryTest extends VeloxCacheExpiryTest {

    @Override
    protected ExpiryEngine<String, Integer> newEngine() {
        return new WheelExpiryEngine<>(1_000_000, 64, 0);
    }
}
