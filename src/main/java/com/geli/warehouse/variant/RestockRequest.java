package com.geli.warehouse.variant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RestockRequest(@NotNull @Positive Integer quantity) {
}
