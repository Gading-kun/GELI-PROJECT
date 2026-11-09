package com.geli.warehouse.variant;

import java.math.BigDecimal;
import java.time.Instant;

public record VariantResponse(
        Long id,
        Long itemId,
        String sku,
        String size,
        String color,
        BigDecimal priceOverride,
        BigDecimal effectivePrice,
        int stock,
        Instant createdAt,
        Instant updatedAt) {

    public static VariantResponse from(Variant variant) {
        return new VariantResponse(
                variant.getId(),
                variant.getItem().getId(),
                variant.getSku(),
                variant.getSize(),
                variant.getColor(),
                variant.getPriceOverride(),
                variant.getEffectivePrice(),
                variant.getStock(),
                variant.getCreatedAt(),
                variant.getUpdatedAt());
    }
}
