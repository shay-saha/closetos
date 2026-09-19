package com.closetos.media.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface MediaAccess {
    Optional<ImageRecord> existingUpload(UUID wardrobe, UUID uploadKey);

    ImageRecord reserve(
            UUID owner, UUID wardrobe, UUID garment, UUID uploadKey, UploadRequest request);

    ImageRecord ownedImage(UUID image, UUID wardrobe);

    Map<UUID, MediaAssets> assetsFor(Collection<UUID> garments, UUID wardrobe);

    List<ImageRecord> imagesFor(UUID garment, UUID wardrobe);

    List<ImageRecord> awaitingUploads();

    Optional<ImageRecord> bySourceKey(String key);

    void deleteGarmentImages(UUID garment, UUID wardrobe);

    void deleteImage(UUID image, UUID wardrobe);
}
