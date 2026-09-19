package com.closetos.wardrobe.api;

import com.closetos.wardrobe.application.WardrobeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wardrobes/current")
class WardrobeController {
    private final WardrobeService wardrobe;

    WardrobeController(WardrobeService wardrobe) {
        this.wardrobe = wardrobe;
    }

    @GetMapping
    WardrobeDetails current() {
        return wardrobe.current();
    }

    @PatchMapping
    WardrobeDetails rename(@Valid @RequestBody RenameWardrobe request) {
        return wardrobe.rename(request.name(), request.version());
    }

    record RenameWardrobe(
            @NotBlank @Size(max = 120) String name, @NotNull @PositiveOrZero Long version) {}
}
