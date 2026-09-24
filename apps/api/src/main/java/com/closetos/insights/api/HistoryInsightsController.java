package com.closetos.insights.api;

import com.closetos.insights.application.WardrobeHistory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HistoryInsightsController {
    private final WardrobeHistory history;

    HistoryInsightsController(WardrobeHistory history) {
        this.history = history;
    }

    @GetMapping("/api/v1/insights/usage")
    HistoryInsights.Usage usage(
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int limit) {
        return history.usage(includeArchived, limit);
    }

    @GetMapping("/api/v1/insights/cost-per-wear")
    HistoryInsights.CostPerWear costPerWear(
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int limit) {
        return history.costPerWear(includeArchived, limit);
    }

    @GetMapping("/api/v1/insights/forgotten")
    HistoryInsights.Forgotten forgotten(
            @RequestParam(required = false) InsightSeason season,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int limit) {
        return history.forgotten(season, limit);
    }
}
