package com.velox.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One customer review. The table that makes {@code CatalogQueryService}'s join genuinely
 * expensive: a product's average rating and review count both require scanning every review
 * row for that product and aggregating, not a single indexed lookup.
 */
@Entity
@Table(name = "review")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int rating;

    @Column(length = 1000)
    private String comment;

    protected Review() {
        // JPA
    }

    public Review(Long productId, int rating, String comment) {
        this.productId = productId;
        this.rating = rating;
        this.comment = comment;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }
}
