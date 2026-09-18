package com.closetos.garment.api;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
public record GarmentMetadata(
        @NotBlank @Size(max = 160) String name,
        @NotNull @Enumerated(EnumType.STRING) GarmentCategory category,
        @Size(max = 80) String subcategory,
        @Size(max = 120) String brand,
        @Size(max = 40) String sizeLabel,
        @Size(max = 60) String primaryColourName,
        @Pattern(regexp = "#[0-9a-fA-F]{6}") String primaryColourHex,
        @Size(max = 12) @JdbcTypeCode(SqlTypes.JSON)
                List<@NotBlank @Size(max = 60) String> secondaryColours,
        @Size(max = 80) String pattern,
        @Size(max = 120) String material,
        @Size(max = 60) String length,
        @Size(max = 60) String formality,
        @Size(max = 12) @JdbcTypeCode(SqlTypes.JSON)
                List<@NotBlank @Size(max = 60) String> seasonTags,
        @Size(max = 24) @JdbcTypeCode(SqlTypes.JSON)
                List<@NotBlank @Size(max = 60) String> occasionTags,
        @Size(max = 24) @JdbcTypeCode(SqlTypes.JSON)
                List<@NotBlank @Size(max = 60) String> styleTags,
        @DecimalMin("0") @DecimalMax("9999999999.99") @Digits(integer = 10, fraction = 2)
                BigDecimal purchasePrice,
        @Pattern(regexp = "[A-Z]{3}")
                @JdbcTypeCode(SqlTypes.CHAR)
                @Column(columnDefinition = "char(3)")
                String purchaseCurrency,
        @PastOrPresent LocalDate purchaseDate,
        @Size(max = 4000) String notes) {
    public GarmentMetadata {
        name = name == null ? null : name.strip();
        secondaryColours = secondaryColours == null ? List.of() : List.copyOf(secondaryColours);
        seasonTags = seasonTags == null ? List.of() : List.copyOf(seasonTags);
        occasionTags = occasionTags == null ? List.of() : List.copyOf(occasionTags);
        styleTags = styleTags == null ? List.of() : List.copyOf(styleTags);
    }
}
