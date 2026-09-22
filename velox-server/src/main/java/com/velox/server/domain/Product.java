package com.velox.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the catalog: the table the demo seeds to a million rows, and the table every
 * read in this application ultimately starts from.
 *
 * <p>{@code categoryId} is a plain column, not a JPA {@code @ManyToOne}, deliberately: the
 * "expensive query" this tier exists to demonstrate ({@code CatalogQueryService}) is a hand
 * written multi-table join, not something an ORM association would hide behind lazy-loading
 * — the whole point is to make the database cost visible, not to abstract it away.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    protected Product() {
        // JPA
    }

    public Product(Long categoryId, String name, String description, long priceCents) {
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.priceCents = priceCents;
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public long getPriceCents() {
        return priceCents;
    }
}
