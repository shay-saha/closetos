package com.closetos.insights.application;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.HistoryInsights.ForgottenPiece;
import com.closetos.insights.api.HistoryInsights.OwnershipBasis;
import com.closetos.insights.api.HistoryInsights.SeasonCompatibility;
import com.closetos.insights.api.InsightSeason;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ForgottenRanker {
    private final ForgottenRankingProperties settings;

    public ForgottenRanker(ForgottenRankingProperties settings) {
        this.settings = settings;
    }

    public int minimumOwnershipDays() {
        return settings.minimumOwnershipDays();
    }

    public int minimumDaysSinceWear() {
        return settings.minimumDaysSinceWear();
    }

    public Optional<RankedPiece> rank(
            GarmentDetails garment, LocalDate today, ZoneId timezone, InsightSeason season) {
        if (garment.status() == GarmentStatus.ARCHIVED) return Optional.empty();
        LocalDate purchased = garment.metadata().purchaseDate();
        var basis = purchased == null ? OwnershipBasis.ADDED_DATE : OwnershipBasis.PURCHASE_DATE;
        LocalDate ownedSince =
                purchased == null ? garment.createdAt().atZone(timezone).toLocalDate() : purchased;
        long ownedDays = Math.max(0, ChronoUnit.DAYS.between(ownedSince, today));
        Long sinceLastWear =
                garment.lastWornAt() == null
                        ? null
                        : Math.max(0, ChronoUnit.DAYS.between(garment.lastWornAt(), today));
        if (ownedDays < settings.minimumOwnershipDays()
                || garment.wearCount() > 0
                        && (sinceLastWear == null
                                || sinceLastWear < settings.minimumDaysSinceWear()))
            return Optional.empty();

        var compatibility =
                season == null
                        ? SeasonCompatibility.NOT_REQUESTED
                        : garment.metadata().seasonTags().isEmpty()
                                ? SeasonCompatibility.UNKNOWN
                                : season.matches(garment.metadata().seasonTags())
                                        ? SeasonCompatibility.MATCH
                                        : SeasonCompatibility.MISMATCH;
        var weights = settings.weights();
        long unwornDays = garment.wearCount() == 0 ? ownedDays : sinceLastWear;
        double seasonSignal =
                switch (compatibility) {
                    case MATCH -> 1;
                    case MISMATCH -> 0;
                    case UNKNOWN, NOT_REQUESTED -> .5;
                };
        double availability =
                switch (garment.status()) {
                    case AVAILABLE -> 1;
                    case LAUNDRY -> settings.availability().laundry();
                    case PACKED -> settings.availability().packed();
                    case LENT -> settings.availability().lent();
                    case ARCHIVED -> 0;
                };
        double score =
                availability
                        * (weights.ownership() * Math.min(ownedDays / 365.0, 1)
                                + weights.recency() * Math.min(unwornDays / 180.0, 1)
                                + weights.infrequency() / (garment.wearCount() + 1.0)
                                + weights.season() * seasonSignal)
                        / weights.total();

        var reasons = new ArrayList<String>();
        reasons.add(
                basis == OwnershipBasis.PURCHASE_DATE
                        ? "Purchase recorded " + ownedDays + " days ago."
                        : "Added " + ownedDays + " days ago; purchase date is unknown.");
        reasons.add(
                garment.wearCount() == 0
                        ? "No wear has been recorded."
                        : garment.wearCount()
                                + " recorded wears; last worn "
                                + sinceLastWear
                                + " days ago.");
        switch (compatibility) {
            case MATCH -> reasons.add("Tagged for the selected season.");
            case MISMATCH ->
                    reasons.add("Season tags do not match the selected season; ranked lower.");
            case UNKNOWN ->
                    reasons.add("Season compatibility is unknown because season tags are missing.");
            case NOT_REQUESTED ->
                    reasons.add("No season selected; season compatibility was not inferred.");
        }
        if (garment.status() != GarmentStatus.AVAILABLE)
            reasons.add(
                    "Currently "
                            + garment.status().name().toLowerCase(java.util.Locale.ROOT)
                            + "; ranked lower because it is unavailable to wear.");
        return Optional.of(
                new RankedPiece(
                        new ForgottenPiece(
                                garment,
                                ownedSince,
                                basis,
                                ownedDays,
                                sinceLastWear,
                                compatibility,
                                java.util.List.copyOf(reasons)),
                        score));
    }

    public record RankedPiece(ForgottenPiece piece, double score) {}
}
