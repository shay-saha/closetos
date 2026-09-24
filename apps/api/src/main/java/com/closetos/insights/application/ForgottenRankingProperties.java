package com.closetos.insights.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("closetos.insights.forgotten")
public record ForgottenRankingProperties(
        @DefaultValue("90") @Min(1) @Max(3650) int minimumOwnershipDays,
        @DefaultValue("30") @Min(1) @Max(3650) int minimumDaysSinceWear,
        @DefaultValue @NotNull @Valid Weights weights,
        @DefaultValue @NotNull @Valid Availability availability) {

    public record Weights(
            @DefaultValue("1") double ownership,
            @DefaultValue("2") double recency,
            @DefaultValue("2") double infrequency,
            @DefaultValue("1") double season) {
        public Weights {
            check(ownership, false);
            check(recency, false);
            check(infrequency, false);
            check(season, false);
            if (ownership + recency + infrequency + season <= 0)
                throw new IllegalArgumentException(
                        "At least one forgotten ranking weight is required.");
        }

        double total() {
            return ownership + recency + infrequency + season;
        }
    }

    public record Availability(
            @DefaultValue("0.35") double laundry,
            @DefaultValue("0.15") double packed,
            @DefaultValue("0.1") double lent) {
        public Availability {
            check(laundry, true);
            check(packed, true);
            check(lent, true);
        }
    }

    private static void check(double value, boolean multiplier) {
        if (!Double.isFinite(value) || value < 0 || value > (multiplier ? 1 : 1000))
            throw new IllegalArgumentException(
                    "Forgotten ranking settings are outside their bounds.");
    }
}
