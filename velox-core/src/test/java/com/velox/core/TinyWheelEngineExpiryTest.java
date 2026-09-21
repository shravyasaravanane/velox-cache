package com.velox.core;

import com.velox.core.expiry.ExpiryEngine;
import com.velox.core.expiry.WheelExpiryEngine;

/**
 * Runs the full expiry suite against the harshest wheel geometry: two slots
 * per level and a 333 ns tick (deliberately not a divisor of anything the
 * tests use). A ten-second TTL then spans dozens of levels of cascading, so
 * any mistake in the hierarchy shows up here first.
 */
class TinyWheelEngineExpiryTest extends VeloxCacheExpiryTest {

    @Override
    protected ExpiryEngine<String, Integer> newEngine() {
        return new WheelExpiryEngine<>(333, 2, 0);
    }
}
