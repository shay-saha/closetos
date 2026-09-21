package com.closetos.collection.api;

import com.closetos.collection.application.CollectionService;
import com.closetos.garment.api.GarmentFilter;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentPresenter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/v1/collections")
class CollectionController {
    private final CollectionService collections;
    private final GarmentPresenter presenter;

    CollectionController(CollectionService collections, GarmentPresenter presenter) {
        this.collections = collections;
        this.presenter = presenter;
    }

    @GetMapping
    CollectionPage list(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) CollectionType type,
            @RequestParam(required = false) @Size(max = 4096) String cursor,
            @RequestParam(defaultValue = "40") @Min(1) @Max(100) int limit) {
        return collections.list(q, type, cursor, limit);
    }

    @PostMapping
    ResponseEntity<CollectionDetails> create(@RequestBody ObjectNode request) {
        var created = collections.create(request);
        return ResponseEntity.created(URI.create("/api/v1/collections/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    CollectionDetails get(@PathVariable UUID id) {
        return collections.get(id);
    }

    @PatchMapping("/{id}")
    CollectionDetails patch(@PathVariable UUID id, @RequestBody ObjectNode request) {
        return collections.patch(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam @PositiveOrZero long version) {
        collections.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/garments")
    GarmentPage garments(@PathVariable UUID id, @Valid @ModelAttribute GarmentFilter filter) {
        return presenter.page(collections.garments(id, filter));
    }
}
