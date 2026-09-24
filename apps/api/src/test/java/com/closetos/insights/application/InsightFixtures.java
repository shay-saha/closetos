package com.closetos.insights.application;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentMetadata;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.media.api.ProcessingStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class InsightFixtures {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    private InsightFixtures() {}

    static GarmentDetails piece(
            String name,
            int wears,
            LocalDate lastWear,
            LocalDate purchased,
            List<String> seasons,
            GarmentStatus status,
            String price,
            String currency) {
        var amount = price == null ? null : new BigDecimal(price);
        var metadata =
                new GarmentMetadata(
                        name,
                        GarmentCategory.TOP,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        seasons,
                        List.of(),
                        List.of(),
                        amount,
                        currency,
                        purchased,
                        null);
        return new GarmentDetails(
                UUID.randomUUID(),
                metadata,
                status,
                ProcessingStatus.READY,
                wears,
                lastWear,
                amount == null
                        ? null
                        : amount.divide(
                                BigDecimal.valueOf(Math.max(wears, 1)), 2, RoundingMode.HALF_UP),
                0,
                Instant.parse("2026-01-01T00:30:00Z"),
                Instant.parse("2026-01-01T00:30:00Z"),
                null);
    }

    static GarmentDetails piece(String name, int wears, String price, String currency) {
        return piece(
                name,
                wears,
                wears == 0 ? null : TODAY.minusDays(180),
                TODAY.minusDays(365),
                List.of("autumn"),
                GarmentStatus.AVAILABLE,
                price,
                currency);
    }

    static ForgottenRankingProperties defaults() {
        return new ForgottenRankingProperties(
                90,
                30,
                new ForgottenRankingProperties.Weights(1, 2, 2, 1),
                new ForgottenRankingProperties.Availability(.35, .15, .1));
    }
}
