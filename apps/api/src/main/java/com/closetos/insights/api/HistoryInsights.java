package com.closetos.insights.api;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class HistoryInsights {
    private HistoryInsights() {}

    public record Usage(
            LocalDate asOf,
            boolean includesArchived,
            int garmentCount,
            long garmentWearOccurrences,
            int neverWornCount,
            int unknownLastWornCount,
            Map<GarmentCategory, Integer> categories,
            Map<GarmentStatus, Integer> availability,
            List<GarmentDetails> mostWorn,
            List<GarmentDetails> leastWorn,
            List<GarmentDetails> neverWorn,
            List<String> explanations) {}

    public record CostPerWear(
            LocalDate asOf,
            boolean includesArchived,
            int garmentCount,
            int missingPriceCount,
            int missingCurrencyCount,
            List<CurrencyCosts> currencies,
            List<String> explanations) {}

    public record CurrencyCosts(
            String currency,
            int pricedGarmentCount,
            BigDecimal knownPurchaseTotal,
            List<GarmentDetails> lowestCostPerWear,
            List<GarmentDetails> highestCostPerWear,
            List<GarmentDetails> neverWorn) {}

    public record Forgotten(
            LocalDate asOf,
            InsightSeason season,
            int minimumOwnershipDays,
            int minimumDaysSinceWear,
            int garmentCount,
            int eligibleCount,
            int unknownLastWornCount,
            List<ForgottenPiece> items,
            List<String> explanations) {}

    public record ForgottenPiece(
            GarmentDetails garment,
            LocalDate ownedSince,
            OwnershipBasis ownershipBasis,
            long ownershipDays,
            Long daysSinceLastWear,
            SeasonCompatibility seasonCompatibility,
            List<String> reasons) {}

    public enum OwnershipBasis {
        PURCHASE_DATE,
        ADDED_DATE
    }

    public enum SeasonCompatibility {
        MATCH,
        MISMATCH,
        UNKNOWN,
        NOT_REQUESTED
    }
}
