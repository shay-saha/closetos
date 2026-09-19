package com.closetos.activity.api;

import com.closetos.activity.application.UploadReservation;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.UploadInstructions;
import com.closetos.media.api.UploadRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UploadController {
    private final UploadReservation reservation;
    private final ObjectStoragePort storage;

    UploadController(UploadReservation reservation, ObjectStoragePort storage) {
        this.reservation = reservation;
        this.storage = storage;
    }

    @PostMapping("/api/v1/garments/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse upload(
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody UploadRequest request) {
        var image = reservation.reserve(null, key, request);
        return new UploadResponse(image.garmentId(), image.id(), storage.signUpload(image));
    }

    @PostMapping("/api/v1/garments/{id}/images")
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse additional(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody UploadRequest request) {
        var image = reservation.reserve(id, key, request);
        return new UploadResponse(image.garmentId(), image.id(), storage.signUpload(image));
    }

    record UploadResponse(UUID garmentId, UUID imageId, UploadInstructions upload) {}
}
