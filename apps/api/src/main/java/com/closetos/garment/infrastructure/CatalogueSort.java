package com.closetos.garment.infrastructure;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentSort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

record CatalogueSort(String expression, boolean ascending, GarmentSort sort) {
    static CatalogueSort of(GarmentSort sort) {
        return switch (sort) {
            case NEWEST -> new CatalogueSort("created_at", false, sort);
            case OLDEST -> new CatalogueSort("created_at", true, sort);
            case NAME -> new CatalogueSort("name COLLATE \"C\"", true, sort);
            case MOST_WORN -> new CatalogueSort("wear_count_cached", false, sort);
            case LEAST_WORN -> new CatalogueSort("wear_count_cached", true, sort);
            case RECENTLY_WORN ->
                    new CatalogueSort("coalesce(last_worn_at, DATE '0001-01-01')", false, sort);
            case COST_PER_WEAR ->
                    new CatalogueSort(
                            "coalesce(round(purchase_price / greatest(wear_count_cached, 1), 2), 9999999999.99)",
                            true,
                            sort);
            case HIGH_COST_PER_WEAR ->
                    new CatalogueSort(
                            "coalesce(round(purchase_price / greatest(wear_count_cached, 1), 2), -1)",
                            false,
                            sort);
        };
    }

    String order() {
        return ascending ? "ASC" : "DESC";
    }

    String comparison() {
        return ascending ? ">" : "<";
    }

    Object parse(String value) {
        return switch (sort) {
            case NEWEST, OLDEST -> Instant.parse(value);
            case NAME -> value;
            case MOST_WORN, LEAST_WORN -> Integer.parseInt(value);
            case RECENTLY_WORN -> LocalDate.parse(value);
            case COST_PER_WEAR, HIGH_COST_PER_WEAR -> new BigDecimal(value);
        };
    }

    String value(GarmentDetails garment) {
        return switch (sort) {
            case NEWEST, OLDEST -> garment.createdAt().toString();
            case NAME -> garment.metadata().name();
            case MOST_WORN, LEAST_WORN -> Integer.toString(garment.wearCount());
            case RECENTLY_WORN ->
                    garment.lastWornAt() == null ? "0001-01-01" : garment.lastWornAt().toString();
            case COST_PER_WEAR, HIGH_COST_PER_WEAR ->
                    garment.costPerWear() == null
                            ? (ascending ? "9999999999.99" : "-1")
                            : garment.costPerWear().toPlainString();
        };
    }
}
