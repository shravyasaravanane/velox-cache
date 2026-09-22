package com.velox.server.cache;

/** The fields a write to a product may change; the request body of every write endpoint. */
public record ProductUpdate(String name, String description, long priceCents) {
}
