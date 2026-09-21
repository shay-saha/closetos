package com.closetos.outfit.api;

import com.closetos.outfit.application.OutfitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/v1/outfits")
class OutfitController {
    private final OutfitService outfits;

    OutfitController(OutfitService outfits) {
        this.outfits = outfits;
    }

    @GetMapping
    OutfitPage list(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) @Size(max = 4096) String cursor,
            @RequestParam(defaultValue = "40") @Min(1) @Max(100) int limit) {
        return outfits.list(q, archived, cursor, limit);
    }

    @PostMapping
    ResponseEntity<OutfitDetails> create(@RequestBody ObjectNode request) {
        var outfit = outfits.create(request);
        return ResponseEntity.created(URI.create("/api/v1/outfits/" + outfit.id())).body(outfit);
    }

    @GetMapping("/{id}")
    OutfitDetails get(@PathVariable UUID id) {
        return outfits.get(id);
    }

    @PatchMapping("/{id}")
    OutfitDetails patch(@PathVariable UUID id, @RequestBody ObjectNode request) {
        return outfits.patch(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam @PositiveOrZero long version) {
        outfits.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/duplicate")
    ResponseEntity<OutfitDetails> duplicate(
            @PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        var copy = outfits.duplicate(id, request.version());
        return ResponseEntity.created(URI.create("/api/v1/outfits/" + copy.id())).body(copy);
    }

    record VersionRequest(@NotNull @PositiveOrZero Long version) {}
}
