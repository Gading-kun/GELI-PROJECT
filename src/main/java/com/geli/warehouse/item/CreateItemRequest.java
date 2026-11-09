package com.geli.warehouse.item;

import com.geli.warehouse.variant.CreateVariantRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record CreateItemRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal basePrice,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid CreateVariantRequest> variants) {
}
