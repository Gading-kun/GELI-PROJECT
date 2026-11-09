package com.geli.warehouse.sale;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateSaleRequest(
        @NotNull(message = "Variant ID is required.")
        @Positive(message = "Variant ID must be greater than zero.")
        Long variantId,
        @NotNull(message = "Quantity is required.")
        @Positive(message = "Quantity must be greater than zero.")
        Integer quantity
) {
}
