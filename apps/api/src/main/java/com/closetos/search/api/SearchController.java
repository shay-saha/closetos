package com.closetos.search.api;

import com.closetos.garment.api.GarmentFilter;
import com.closetos.search.application.WardrobeSearch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {
    private final WardrobeSearch search;

    SearchController(WardrobeSearch search) {
        this.search = search;
    }

    @GetMapping("/api/v1/search")
    SearchPage get(
            @Valid @ModelAttribute GarmentFilter filter,
            @RequestParam(required = false) SearchMode mode,
            @RequestParam(required = false) UUID similarToGarmentId) {
        return search.find(filter, mode, similarToGarmentId, null);
    }

    @GetMapping("/api/v1/garments/{id}/similar")
    SearchPage similar(@PathVariable UUID id, @Valid @ModelAttribute GarmentFilter filter) {
        return search.find(filter, SearchMode.SEMANTIC, id, null);
    }

    @PostMapping("/api/v1/search/image")
    SearchPage image(@Valid @RequestBody ImageQuery request) {
        return search.find(request.filters(), request.mode(), null, request.imageBase64());
    }

    record ImageQuery(
            @Valid GarmentFilter filters,
            SearchMode mode,
            @NotBlank @Size(max = 11_184_812) String imageBase64) {}
}
