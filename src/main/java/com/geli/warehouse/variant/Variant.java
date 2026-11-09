package com.geli.warehouse.variant;

import com.geli.warehouse.common.error.ConflictException;
import com.geli.warehouse.common.error.InvalidRequestException;
import com.geli.warehouse.item.Item;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "variants")
public class Variant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(length = 50)
    private String size;

    @Column(length = 50)
    private String color;

    @Column(name = "price_override", precision = 19, scale = 2)
    private BigDecimal priceOverride;

    @Column(nullable = false)
    private int stock;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Variant() {
    }

    public Variant(Item item, String sku, String size, String color, BigDecimal priceOverride, int stock) {
        this.item = item;
        this.sku = sku;
        this.size = size;
        this.color = color;
        this.priceOverride = priceOverride;
        this.stock = stock;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void updateDetails(String sku, String size, String color, BigDecimal priceOverride) {
        this.sku = sku;
        this.size = size;
        this.color = color;
        this.priceOverride = priceOverride;
        this.updatedAt = Instant.now();
    }

    public void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new InvalidRequestException("Sale quantity must be greater than zero.");
        }
        if (quantity > stock) {
            throw new ConflictException("Insufficient stock for variant " + id + ".");
        }
        stock -= quantity;
        updatedAt = Instant.now();
    }

    public void restock(int quantity) {
        if (quantity <= 0) {
            throw new InvalidRequestException("Restock quantity must be greater than zero.");
        }
        if ((long) stock + quantity > Integer.MAX_VALUE) {
            throw new ConflictException("Restock would exceed the maximum stock quantity.");
        }
        stock += quantity;
        updatedAt = Instant.now();
    }

    public BigDecimal getEffectivePrice() {
        return priceOverride == null ? item.getBasePrice() : priceOverride;
    }

    public Long getId() {
        return id;
    }

    public Item getItem() {
        return item;
    }

    public String getSku() {
        return sku;
    }

    public String getSize() {
        return size;
    }

    public String getColor() {
        return color;
    }

    public BigDecimal getPriceOverride() {
        return priceOverride;
    }

    public int getStock() {
        return stock;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
