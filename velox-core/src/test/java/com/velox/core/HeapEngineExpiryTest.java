package com.velox.core;

import com.velox.core.expiry.ExpiryEngine;
import com.velox.core.expiry.HeapExpiryEngine;

/** Runs the full expiry suite against the indexed min-heap. */
class HeapEngineExpiryTest extends VeloxCacheExpiryTest {

    @Override
    protected ExpiryEngine<String, Integer> newEngine() {
        return new HeapExpiryEngine<>();
    }
}
