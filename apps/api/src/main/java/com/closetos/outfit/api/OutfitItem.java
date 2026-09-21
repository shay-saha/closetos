package com.closetos.outfit.api;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

@Embeddable
public record OutfitItem(
        @NotNull UUID garmentId,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal x,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal y,
        @NotNull @DecimalMin("0.1") @DecimalMax("3") BigDecimal scale,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal rotation,
        @NotNull @Min(0) @Max(1000) Integer zIndex) {}
