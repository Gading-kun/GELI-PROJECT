package com.geli.warehouse.item;

import com.geli.warehouse.variant.VariantResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ItemResponse(
        Long id,
        String name,
        String description,
        BigDecimal basePrice,
        long totalStock,
        List<VariantResponse> variants,
        Instant createdAt,
        Instant updatedAt) {

    public static ItemResponse from(Item item, List<VariantResponse> variants) {
        long totalStock = variants.stream().mapToLong(VariantResponse::stock).sum();
        return new ItemResponse(
                item.getId(),
                item.getName(),
                item.getDescription(),
                item.getBasePrice(),
                totalStock,
                List.copyOf(variants),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
