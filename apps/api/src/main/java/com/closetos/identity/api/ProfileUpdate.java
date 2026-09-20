package com.closetos.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ProfileUpdate(
        @NotBlank @Size(max = 120) String displayName,
        @NotBlank @Size(max = 35) String locale,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 80) String timezone,
        @NotNull Boolean deleteOriginalAfterIsolation,
        @NotNull @PositiveOrZero Long version) {}
