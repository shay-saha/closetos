package com.closetos.media.application;

import com.closetos.media.api.AssetDescriptor;
import com.closetos.media.api.ImageRecord;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.MediaAssets;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.UploadRequest;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.OutboxAccess;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MediaService implements MediaAccess {
    private static final String COLUMNS =
            "id, garment_id, wardrobe_id, user_id, source_s3_key, mime_type, expected_size, source_checksum, processing_status, assets::text, delete_original_after_isolation, original_filename, image_role";
    private final JdbcClient jdbc;
    private final UploadPolicy policy;
    private final OutboxAccess outbox;
    private final ObjectStoragePort storage;
    private final JsonMapper json;
    private final Clock clock;

    public MediaService(
            JdbcClient jdbc,
            UploadPolicy policy,
            OutboxAccess outbox,
            ObjectStoragePort storage,
            JsonMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.outbox = outbox;
        this.storage = storage;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<ImageRecord> existingUpload(UUID wardrobe, UUID key) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM garment_image WHERE wardrobe_id = :wardrobe AND upload_key = :key")
                .param("wardrobe", wardrobe)
                .param("key", key)
                .query(ImageRecord.class)
                .optional();
    }

    @Override
    @Transactional
    public ImageRecord reserve(
            UUID owner, UUID wardrobe, UUID garment, UUID key, UploadRequest request) {
        UUID image = UUID.randomUUID();
        String source =
                "users/"
                        + owner
                        + "/garments/"
                        + garment
                        + "/images/"
                        + image
                        + "/original."
                        + policy.extension(request);
        jdbc.sql(
                        """
                INSERT INTO garment_image (id, garment_id, wardrobe_id, user_id, image_role, original_filename,
                    mime_type, source_s3_key, expected_size, source_checksum, upload_key, processing_status,
                    delete_original_after_isolation)
                VALUES (:id, :garment, :wardrobe, :owner, :role, :filename, :mime, :source, :size, :checksum,
                    :key, 'AWAITING_UPLOAD', (SELECT delete_original_after_isolation FROM user_profile WHERE id = :owner))
                """)
                .param("id", image)
                .param("garment", garment)
                .param("wardrobe", wardrobe)
                .param("owner", owner)
                .param("role", request.imageRole().name())
                .param("filename", request.filename())
                .param("mime", request.mimeType())
                .param("source", source)
                .param("size", request.size())
                .param("checksum", request.checksumSha256())
                .param("key", key)
                .update();
        return ownedImage(image, wardrobe);
    }

    @Override
    public ImageRecord ownedImage(UUID id, UUID wardrobe) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM garment_image WHERE id = :id AND wardrobe_id = :wardrobe")
                .param("id", id)
                .param("wardrobe", wardrobe)
                .query(ImageRecord.class)
                .optional()
                .orElseThrow(() -> DomainException.notFound("Image"));
    }

    @Override
    public Map<UUID, MediaAssets> assetsFor(Collection<UUID> garments, UUID wardrobe) {
        if (garments.isEmpty()) return Map.of();
        Map<UUID, MediaAssets> result = new HashMap<>();
        var images =
                jdbc.sql(
                                "SELECT "
                                        + COLUMNS
                                        + " FROM garment_image WHERE wardrobe_id = :wardrobe AND garment_id IN (:garments) AND assets IS NOT NULL ORDER BY (image_role = 'FRONT') DESC, created_at")
                        .param("wardrobe", wardrobe)
                        .param("garments", garments)
                        .query(ImageRecord.class)
                        .list();
        java.time.Instant expiry = clock.instant().plus(Duration.ofMinutes(15));
        for (ImageRecord image : images)
            result.computeIfAbsent(image.garmentId(), ignored -> signedAssets(image, expiry));
        return Map.copyOf(result);
    }

    private MediaAssets signedAssets(ImageRecord image, java.time.Instant expiry) {
        Map<String, AssetDescriptor> assets =
                json.readValue(image.assets(), new TypeReference<>() {});
        AssetDescriptor isolated = assets.get("isolated");
        return new MediaAssets(
                image.id(),
                storage.signDownload(isolated.key(), expiry),
                storage.signDownload(assets.get("display").key(), expiry),
                storage.signDownload(assets.get("card").key(), expiry),
                storage.signDownload(assets.get("thumbnail").key(), expiry),
                isolated.width(),
                isolated.height(),
                expiry);
    }

    @Override
    public List<ImageRecord> imagesFor(UUID garment, UUID wardrobe) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM garment_image WHERE garment_id = :garment AND wardrobe_id = :wardrobe ORDER BY created_at")
                .param("garment", garment)
                .param("wardrobe", wardrobe)
                .query(ImageRecord.class)
                .list();
    }

    @Override
    public List<ImageRecord> awaitingUploads() {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM garment_image WHERE processing_status = 'AWAITING_UPLOAD' ORDER BY created_at LIMIT 100")
                .query(ImageRecord.class)
                .list();
    }

    @Override
    public Optional<ImageRecord> bySourceKey(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM garment_image WHERE source_s3_key = :key")
                .param("key", key)
                .query(ImageRecord.class)
                .optional();
    }

    @Override
    @Transactional
    public void deleteGarmentImages(UUID garment, UUID wardrobe) {
        for (var image : imagesFor(garment, wardrobe)) deleteImage(image.id(), wardrobe);
    }

    @Override
    @Transactional
    public void deleteImage(UUID id, UUID wardrobe) {
        ImageRecord image = ownedImage(id, wardrobe);
        outbox.enqueue(
                "image",
                id,
                "DELETE_MEDIA",
                "delete-media:" + id,
                Map.of("prefix", image.prefix()));
        jdbc.sql("DELETE FROM garment_image WHERE id = :id AND wardrobe_id = :wardrobe")
                .param("id", id)
                .param("wardrobe", wardrobe)
                .update();
    }
}
