package com.closetos.garment.api;

import com.closetos.garment.application.GarmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/v1/garments")
class GarmentController {
    private final GarmentService garments;

    @GetMapping
    GarmentPage list(@Valid @ModelAttribute GarmentFilter filter) {
        return garments.list(filter);
    }

    GarmentController(GarmentService garments) {
        this.garments = garments;
    }

    @PostMapping
    ResponseEntity<GarmentDetails> create(@Valid @RequestBody GarmentMetadata request) {
        GarmentDetails garment = garments.create(request);
        return ResponseEntity.created(URI.create("/api/v1/garments/" + garment.id())).body(garment);
    }

    @GetMapping("/{id}")
    GarmentDetails get(@PathVariable UUID id) {
        return garments.get(id);
    }

    @PatchMapping("/{id}")
    GarmentDetails patch(@PathVariable UUID id, @RequestBody ObjectNode patch) {
        return garments.patch(id, patch);
    }

    @PostMapping("/{id}/status")
    GarmentDetails status(@PathVariable UUID id, @Valid @RequestBody StatusChange request) {
        return garments.status(id, request.status(), request.version());
    }

    @PostMapping("/{id}/archive")
    GarmentDetails archive(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return garments.status(id, GarmentStatus.ARCHIVED, request.version());
    }

    @PostMapping("/{id}/restore")
    GarmentDetails restore(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return garments.status(id, GarmentStatus.AVAILABLE, request.version());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version) {
        garments.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    record StatusChange(@NotNull GarmentStatus status, @NotNull @PositiveOrZero Long version) {}

    record VersionRequest(@NotNull @PositiveOrZero Long version) {}
}
