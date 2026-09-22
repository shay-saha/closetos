package com.closetos.garment.api;

import com.closetos.media.api.ProcessingStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record GarmentFilter(
        GarmentCategory category,
        @Size(max = 80) String subcategory,
        GarmentStatus status,
        ProcessingStatus processingStatus,
        @Size(max = 60) String season,
        @Size(max = 60) String occasion,
        @Size(max = 60) String colour,
        @Size(max = 120) String brand,
        @Size(max = 40) String size,
        @Size(max = 60) String formality,
        @Size(max = 60) String tag,
        @Size(max = 500) String q,
        @PositiveOrZero Integer minWearCount,
        @PositiveOrZero Integer maxWearCount,
        LocalDate notWornSince,
        LocalDate purchasedAfter,
        LocalDate purchasedBefore,
        GarmentView view,
        GarmentSort sort,
        @Size(max = 2048) String cursor,
        @Min(1) @Max(100) Integer limit) {
    public GarmentFilter {
        subcategory = clean(subcategory);
        season = clean(season);
        occasion = clean(occasion);
        colour = clean(colour);
        brand = clean(brand);
        size = clean(size);
        formality = clean(formality);
        tag = clean(tag);
        q = clean(q);
        sort = sort == null ? (view == null ? GarmentSort.NEWEST : view.defaultSort()) : sort;
        limit = limit == null ? 40 : limit;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
