package com.closetos.search.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.search.api.EmbeddingProviderPort.Input;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EmbeddingInputLoader {
    private final GarmentAccess garments;
    private final MediaAccess media;
    private final JsonMapper json;

    public EmbeddingInputLoader(GarmentAccess garments, MediaAccess media, JsonMapper json) {
        this.garments = garments;
        this.media = media;
        this.json = json;
    }

    @Transactional
    public Optional<Material> load(UUID garment, UUID wardrobe) {
        return garments.embeddingSnapshot(garment, wardrobe)
                .filter(snapshot -> snapshot.processingStatus() == ProcessingStatus.READY)
                .map(
                        snapshot -> {
                            var metadata = snapshot.metadata();
                            var parts = new ArrayList<String>();
                            parts.add(metadata.name());
                            parts.add(
                                    metadata.category().name().toLowerCase(java.util.Locale.ROOT));
                            for (String value :
                                    new String[] {
                                        metadata.subcategory(),
                                        metadata.primaryColourName(),
                                        metadata.material(),
                                        metadata.pattern(),
                                        metadata.length(),
                                        metadata.formality(),
                                        metadata.brand()
                                    }) if (value != null && !value.isBlank()) parts.add(value);
                            for (var tags :
                                    List.of(
                                            metadata.seasonTags(),
                                            metadata.occasionTags(),
                                            metadata.styleTags())) parts.addAll(tags);
                            if (metadata.notes() != null) parts.add(metadata.notes());
                            String text = String.join("; ", parts);
                            text = text.substring(0, Math.min(text.length(), 1000));
                            var image = media.embeddingImage(garment, wardrobe).orElse(null);
                            var input =
                                    new Input(
                                            text,
                                            image == null ? null : image.key(),
                                            image == null ? null : image.checksumSha256(),
                                            null);
                            return new Material(
                                    garment,
                                    wardrobe,
                                    input,
                                    digest(json.writeValueAsString(input)));
                        });
    }

    private String digest(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Material(UUID garmentId, UUID wardrobeId, Input input, String fingerprint) {}
}
