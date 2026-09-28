package com.closetos.media.infrastructure;

import com.closetos.media.api.PersonalPhotoDownloads;
import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class PhotoPersonalData implements PersonalDataContributor {
    private final PersonalPhotoDownloads downloads;

    PhotoPersonalData(PersonalPhotoDownloads downloads) {
        this.downloads = downloads;
    }

    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "images",
                "SELECT to_jsonb(i) FROM garment_image i WHERE user_id = ? ORDER BY id",
                owner,
                image -> links(image, owner, photoExpiry));
        writer.rows(
                "processingHistory",
                "SELECT to_jsonb(j) - 'execution_arn' FROM processing_job j JOIN garment_image i ON i.id = j.image_id WHERE i.user_id = ? ORDER BY j.id",
                owner);
    }

    private JsonNode links(JsonNode value, UUID owner, Instant expiresAt) {
        var image = (ObjectNode) value;
        UUID garment = UUID.fromString(image.path("garment_id").asText());
        UUID id = UUID.fromString(image.path("id").asText());
        String source = image.path("source_s3_key").asText();
        String checksum = image.path("source_checksum").asText();
        JsonNode assets = image.path("assets");
        image.remove(
                List.of("source_s3_key", "source_checksum", "upload_key", "assets", "user_id"));
        var files = image.putArray("files");
        if (!image.path("original_deleted").asBoolean()
                && !List.of("DRAFT", "AWAITING_UPLOAD")
                        .contains(image.path("processing_status").asText())) {
            files.addObject()
                    .put("role", "original")
                    .put("url", downloads.sign(owner, garment, id, source, expiresAt))
                    .put("mimeType", image.path("mime_type").asText())
                    .put("checksumSha256", checksum);
        }
        for (String role : List.of("isolated", "display", "card", "thumbnail", "mask")) {
            JsonNode asset = assets.path(role);
            if (!asset.isObject()) continue;
            var file = ((ObjectNode) asset).deepCopy();
            file.remove("key");
            file.put("role", role)
                    .put(
                            "url",
                            downloads.sign(
                                    owner, garment, id, asset.path("key").asText(), expiresAt));
            files.add(file);
        }
        return image;
    }
}
