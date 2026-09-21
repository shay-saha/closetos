package com.closetos.outfit.api;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
public record OutfitMetadata(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 80) String occasion,
        @Size(max = 60) String season,
        @Min(1) @Max(5) Integer rating,
        @Size(max = 24) @JdbcTypeCode(SqlTypes.JSON) List<@NotBlank @Size(max = 60) String> tags,
        @Size(max = 4000) String notes,
        boolean archived) {
    public OutfitMetadata {
        name = name == null ? null : name.strip();
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
