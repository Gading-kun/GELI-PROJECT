package com.geli.warehouse.sale;

import java.math.BigDecimal;
import java.time.Instant;

public record SaleResponse(
        Long id,
        Long variantId,
        String sku,
        String itemName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        Instant createdAt
) {

    public static SaleResponse from(Sale sale) {
        return new SaleResponse(
                sale.getId(),
                sale.getVariant().getId(),
                sale.getSkuSnapshot(),
                sale.getItemNameSnapshot(),
                sale.getQuantity(),
                sale.getUnitPrice(),
                sale.getTotalAmount(),
                sale.getCreatedAt()
        );
    }
}
