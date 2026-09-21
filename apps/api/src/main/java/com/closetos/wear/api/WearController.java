package com.closetos.wear.api;

import com.closetos.wear.application.WearService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class WearController {
    private final WearService wear;

    WearController(WearService wear) {
        this.wear = wear;
    }

    @PostMapping("/wear-events")
    ResponseEntity<WearDetails> log(
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody GarmentWear request) {
        return created(
                wear.log(
                        key,
                        null,
                        0,
                        request.garmentIds(),
                        request.wornOn(),
                        request.notes(),
                        request.context()));
    }

    @PostMapping("/outfits/{id}/wear")
    ResponseEntity<WearDetails> outfit(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody OutfitWear request) {
        return created(
                wear.log(
                        key,
                        id,
                        request.version(),
                        List.of(),
                        request.wornOn(),
                        request.notes(),
                        request.context()));
    }

    @GetMapping("/wear-events")
    WearPage list(
            @RequestParam(required = false) UUID garmentId,
            @RequestParam(required = false) UUID outfitId,
            @RequestParam(required = false) @Size(max = 4096) String cursor,
            @RequestParam(defaultValue = "40") @Min(1) @Max(100) int limit) {
        return wear.list(garmentId, outfitId, cursor, limit);
    }

    @DeleteMapping("/wear-events/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        wear.remove(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/wear-events/{id}")
    WearDetails get(@PathVariable UUID id) {
        return wear.get(id);
    }

    @PostMapping("/wear-events/recalculate")
    ResponseEntity<Void> repair() {
        wear.repair();
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<WearDetails> created(WearDetails event) {
        return ResponseEntity.created(URI.create("/api/v1/wear-events/" + event.id())).body(event);
    }

    record GarmentWear(
            @NotEmpty @Size(max = 50) List<@NotNull UUID> garmentIds,
            @NotNull @PastOrPresent LocalDate wornOn,
            @Size(max = 4000) String notes,
            @Size(max = 120) String context) {}

    record OutfitWear(
            @NotNull @PositiveOrZero Long version,
            @NotNull @PastOrPresent LocalDate wornOn,
            @Size(max = 4000) String notes,
            @Size(max = 120) String context) {}
}
