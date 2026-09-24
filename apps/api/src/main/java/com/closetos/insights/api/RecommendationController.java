package com.closetos.insights.api;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.insights.api.RecommendationInsights.Duplicates;
import com.closetos.insights.api.RecommendationInsights.WorksWith;
import com.closetos.insights.application.WardrobeRecommendations;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RecommendationController {
    private final WardrobeRecommendations recommendations;

    RecommendationController(WardrobeRecommendations recommendations) {
        this.recommendations = recommendations;
    }

    @GetMapping("/api/v1/insights/duplicates")
    Duplicates duplicates(
            @RequestParam(required = false) GarmentCategory category,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int limit) {
        return recommendations.duplicates(category, limit);
    }

    @GetMapping("/api/v1/garments/{id}/works-with")
    WorksWith worksWith(
            @PathVariable UUID id,
            @RequestParam(required = false) InsightSeason season,
            @RequestParam(required = false) @Size(max = 60) String formality,
            @RequestParam(required = false) RecommendationWeather weather,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int limit) {
        return recommendations.worksWith(id, new PairingContext(season, formality, weather), limit);
    }
}
