package com.closetos.activity.api;

import com.closetos.activity.application.ProcessingTransitions;
import com.closetos.garment.api.GarmentAccess;
import com.closetos.media.api.ImageRole;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.ProcessingAccess;
import com.closetos.media.api.ProcessingAccess.ProcessingSnapshot;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProcessingController {
    private final GarmentAccess garments;
    private final MediaAccess media;
    private final ProcessingAccess processing;
    private final ProcessingTransitions transitions;
    private final WardrobeAccess wardrobes;

    ProcessingController(
            GarmentAccess garments,
            MediaAccess media,
            ProcessingAccess processing,
            ProcessingTransitions transitions,
            WardrobeAccess wardrobes) {
        this.garments = garments;
        this.media = media;
        this.processing = processing;
        this.transitions = transitions;
        this.wardrobes = wardrobes;
    }

    @GetMapping("/api/v1/processing/{imageId}")
    ProcessingSnapshot processing(@PathVariable UUID imageId) {
        UUID wardrobe = wardrobes.currentWardrobeId();
        media.ownedImage(imageId, wardrobe);
        return processing.snapshot(imageId, wardrobe);
    }

    @GetMapping("/api/v1/garments/{id}/images")
    List<ImageDetails> images(@PathVariable UUID id) {
        garments.owned(id);
        return media.imagesFor(id, wardrobes.currentWardrobeId()).stream()
                .map(
                        image ->
                                new ImageDetails(
                                        image.id(),
                                        image.imageRole(),
                                        image.originalFilename(),
                                        image.processingStatus()))
                .toList();
    }

    @PostMapping("/api/v1/garments/{id}/processing/retry")
    ProcessingSnapshot retry(@PathVariable UUID id) {
        garments.owned(id);
        UUID wardrobe = wardrobes.currentWardrobeId();
        var image =
                media.imagesFor(id, wardrobe).stream()
                        .filter(
                                candidate ->
                                        candidate.processingStatus() == ProcessingStatus.FAILED)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        DomainException.invalid(
                                                "There is no failed photograph to retry."));
        transitions.retry(image.id(), wardrobe);
        return processing.snapshot(image.id(), wardrobe);
    }

    @DeleteMapping("/api/v1/garments/{garmentId}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID garmentId, @PathVariable UUID imageId) {
        garments.owned(garmentId);
        UUID wardrobe = wardrobes.currentWardrobeId();
        if (!media.ownedImage(imageId, wardrobe).garmentId().equals(garmentId))
            throw DomainException.notFound("Photograph not found.");
        media.deleteImage(imageId, wardrobe);
    }

    record ImageDetails(
            UUID id, ImageRole role, String filename, ProcessingStatus processingStatus) {}
}
