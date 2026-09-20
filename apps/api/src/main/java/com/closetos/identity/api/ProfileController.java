package com.closetos.identity.api;

import com.closetos.identity.application.ProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
class ProfileController {
    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    ProfileDetails current() {
        return profiles.current();
    }

    @PatchMapping
    ProfileDetails update(@Valid @RequestBody ProfileUpdate request) {
        return profiles.update(request);
    }
}
