package com.closetos.packing.api;

import com.closetos.packing.application.PackingLists;
import com.closetos.packing.application.PackingOptimisation;
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
@RequestMapping("/api/v1/packing-lists")
class PackingController {
    private final PackingLists lists;
    private final PackingOptimisation optimisation;

    PackingController(PackingLists lists, PackingOptimisation optimisation) {
        this.lists = lists;
        this.optimisation = optimisation;
    }

    @GetMapping
    PackingDetails.Page list(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) @Size(max = 4096) String cursor,
            @RequestParam(defaultValue = "40") @Min(1) @Max(100) int limit) {
        return lists.list(q, cursor, limit);
    }

    @PostMapping
    ResponseEntity<PackingDetails> create(@RequestBody ObjectNode request) {
        var created = lists.create(request);
        return ResponseEntity.created(URI.create("/api/v1/packing-lists/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    PackingDetails get(@PathVariable UUID id) {
        return lists.get(id);
    }

    @PatchMapping("/{id}")
    PackingDetails patch(@PathVariable UUID id, @RequestBody ObjectNode request) {
        return lists.patch(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam @PositiveOrZero long version) {
        lists.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/optimise")
    PackingDetails optimise(@PathVariable UUID id, @RequestBody @Valid Version request) {
        return optimisation.optimise(id, request.version());
    }

    @PostMapping("/{id}/manual")
    PackingDetails manual(@PathVariable UUID id, @RequestBody @Valid ManualOverride request) {
        return optimisation.override(id, request.version(), request.plan());
    }

    @PatchMapping("/{id}/items/{garmentId}")
    PackingDetails packed(
            @PathVariable UUID id,
            @PathVariable UUID garmentId,
            @RequestBody @Valid PackedItem request) {
        return lists.packed(
                id, garmentId, request.version(), request.garmentVersion(), request.packed());
    }

    record Version(@NotNull @PositiveOrZero Long version) {}

    record ManualOverride(
            @NotNull @PositiveOrZero Long version, @NotNull @Valid PackingSolution plan) {}

    record PackedItem(
            @NotNull @PositiveOrZero Long version,
            @NotNull @PositiveOrZero Long garmentVersion,
            @NotNull Boolean packed) {}
}
