package com.velox.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stock for one product. A 1:1 extension of {@link Product}, kept as its own table on purpose -- see {@link Product}. */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "stock_count", nullable = false)
    private int stockCount;

    @Column(name = "warehouse_location", length = 50)
    private String warehouseLocation;

    protected Inventory() {
        // JPA
    }

    public Inventory(Long productId, int stockCount, String warehouseLocation) {
        this.productId = productId;
        this.stockCount = stockCount;
        this.warehouseLocation = warehouseLocation;
    }

    public Long getProductId() {
        return productId;
    }

    public int getStockCount() {
        return stockCount;
    }

    public String getWarehouseLocation() {
        return warehouseLocation;
    }
}
