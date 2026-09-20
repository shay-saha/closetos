package com.closetos.activity.api;

import com.closetos.activity.application.SuggestionReview;
import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentPresenter;
import com.closetos.intelligence.api.SuggestionAccess;
import com.closetos.intelligence.api.SuggestionDetails;
import com.closetos.wardrobe.api.WardrobeAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/v1/garments/{garmentId}/suggestions")
class SuggestionController {
    private final GarmentAccess garments;
    private final WardrobeAccess wardrobes;
    private final SuggestionAccess suggestions;
    private final SuggestionReview review;
    private final GarmentPresenter presenter;

    SuggestionController(
            GarmentAccess garments,
            WardrobeAccess wardrobes,
            SuggestionAccess suggestions,
            SuggestionReview review,
            GarmentPresenter presenter) {
        this.garments = garments;
        this.wardrobes = wardrobes;
        this.suggestions = suggestions;
        this.review = review;
        this.presenter = presenter;
    }

    @GetMapping
    List<SuggestionDetails> list(@PathVariable UUID garmentId) {
        garments.owned(garmentId);
        return suggestions.list(garmentId, wardrobes.currentWardrobeId());
    }

    @PostMapping("/accept")
    GarmentDetails accept(@PathVariable UUID garmentId, @Valid @RequestBody Accept request) {
        return presenter.detail(
                review.accept(
                        garmentId,
                        request.suggestionId(),
                        request.suggestionVersion(),
                        request.garmentVersion(),
                        request.corrections()));
    }

    @PostMapping("/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable UUID garmentId, @Valid @RequestBody Reject request) {
        review.reject(garmentId, request.suggestionId(), request.suggestionVersion());
    }

    record Accept(
            @NotNull UUID suggestionId,
            @NotNull @PositiveOrZero Long suggestionVersion,
            @NotNull @PositiveOrZero Long garmentVersion,
            @NotNull ObjectNode corrections) {}

    record Reject(@NotNull UUID suggestionId, @NotNull @PositiveOrZero Long suggestionVersion) {}
}
