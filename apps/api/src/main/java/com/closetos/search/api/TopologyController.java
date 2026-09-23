package com.closetos.search.api;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.search.application.WardrobeTopology;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TopologyController {
    private final WardrobeTopology topology;

    TopologyController(WardrobeTopology topology) {
        this.topology = topology;
    }

    @GetMapping("/api/v1/insights/topology")
    WardrobeGraph graph(
            @RequestParam(required = false) GarmentCategory category,
            @RequestParam(defaultValue = "3") @Min(2) @Max(5) int neighbours,
            @RequestParam(defaultValue = "1000") @Min(1) @Max(2000) int limit) {
        return topology.graph(category, neighbours, limit);
    }
}
