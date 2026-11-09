package com.geli.warehouse.item;

import java.math.BigDecimal;
import java.time.Instant;

public record ItemSummaryResponse(
        Long id,
        String name,
        String description,
        BigDecimal basePrice,
        Instant createdAt,
        Instant updatedAt) {

    public static ItemSummaryResponse from(Item item) {
        return new ItemSummaryResponse(
                item.getId(),
                item.getName(),
                item.getDescription(),
                item.getBasePrice(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
