package com.closetos.activity.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.identity.api.IdentityAccess;
import com.closetos.media.api.ImageRecord;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.UploadRequest;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadReservation {
    private final GarmentAccess garments;
    private final IdentityAccess identity;
    private final WardrobeAccess wardrobes;
    private final MediaAccess media;
    private final JdbcClient jdbc;

    public UploadReservation(
            GarmentAccess garments,
            IdentityAccess identity,
            WardrobeAccess wardrobes,
            MediaAccess media,
            JdbcClient jdbc) {
        this.garments = garments;
        this.identity = identity;
        this.wardrobes = wardrobes;
        this.media = media;
        this.jdbc = jdbc;
    }

    @Transactional
    public ImageRecord reserve(UUID garmentId, UUID uploadKey, UploadRequest request) {
        UUID wardrobe = wardrobes.currentWardrobeId();
        UUID owner = identity.currentUserId();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", wardrobe + ":upload:" + uploadKey)
                .query()
                .singleRow();
        var existing = media.existingUpload(wardrobe, uploadKey);
        if (existing.isPresent()) {
            ImageRecord image = existing.get();
            if (!image.sourceChecksum().equals(request.checksumSha256())
                    || image.expectedSize() != request.size()
                    || !image.mimeType().equals(request.mimeType())
                    || !image.originalFilename().equals(request.filename())
                    || image.imageRole() != request.imageRole()
                    || (garmentId != null && !image.garmentId().equals(garmentId))) {
                throw DomainException.conflict();
            }
            return image;
        }
        UUID garment =
                garmentId == null
                        ? garments.createDraft(wardrobe).id()
                        : garments.owned(garmentId).id();
        return media.reserve(owner, wardrobe, garment, uploadKey, request);
    }
}
