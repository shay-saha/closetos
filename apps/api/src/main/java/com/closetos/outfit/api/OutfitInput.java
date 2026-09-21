package com.closetos.outfit.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OutfitInput(
        @Valid @NotNull OutfitMetadata metadata,
        @NotNull @Size(max = 50) List<@NotNull @Valid OutfitItem> items) {}
