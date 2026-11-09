package com.geli.warehouse.variant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateVariantRequest(
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*", message = "must contain only letters, numbers, dots, underscores, or hyphens")
        String sku,
        @Size(max = 50) String size,
        @Size(max = 50) String color,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal priceOverride,
        @NotNull @PositiveOrZero Integer initialStock) {
}
