package com.closetos.search.infrastructure;

import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort.Input;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

final class BedrockEmbeddingImages {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Pattern DERIVATIVE =
            Pattern.compile(
                    "users/[0-9a-f-]{36}/garments/[0-9a-f-]{36}/images/[0-9a-f-]{36}/pipelines/\\d+-r[1-5]/(?:display|card)\\.(?:png|webp)");
    private final S3Client storage;
    private final String bucket;

    BedrockEmbeddingImages(S3Client storage, String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    String prepare(Input input) {
        if (input.imageKey() != null && input.imageBase64() != null
                || (input.imageKey() != null) != (input.imageChecksumSha256() != null))
            throw DomainException.invalid(
                    "Provide one photograph, with a checksum for stored images.");
        if (input.imageKey() == null && input.imageBase64() == null) return null;
        byte[] data;
        try {
            if (input.imageKey() != null) {
                if (!DERIVATIVE.matcher(input.imageKey()).matches())
                    throw DomainException.invalid(
                            "Only verified garment derivatives can be embedded.");
                byte[] expected;
                try {
                    if (input.imageChecksumSha256().length() != 44)
                        throw new IllegalArgumentException();
                    expected = Base64.getDecoder().decode(input.imageChecksumSha256());
                    if (expected.length != 32) throw new IllegalArgumentException();
                } catch (IllegalArgumentException exception) {
                    throw DomainException.invalid(
                            "A SHA-256 checksum is required for stored photographs.");
                }
                try (var source =
                        storage.getObject(
                                GetObjectRequest.builder()
                                        .bucket(bucket)
                                        .key(input.imageKey())
                                        .build())) {
                    if (source.response().contentLength() > MAX_BYTES) {
                        source.abort();
                        throw DomainException.invalid("Use a photograph smaller than 8 MB.");
                    }
                    data = source.readNBytes(MAX_BYTES + 1);
                    if (data.length > MAX_BYTES) {
                        source.abort();
                        throw DomainException.invalid("Use a photograph smaller than 8 MB.");
                    }
                }
                if (!MessageDigest.isEqual(
                        expected, MessageDigest.getInstance("SHA-256").digest(data)))
                    throw new IllegalStateException(
                            "Stored embedding photograph failed checksum verification.");
            } else {
                try {
                    if (input.imageBase64().length() > 11_184_812)
                        throw new IllegalArgumentException();
                    data = Base64.getDecoder().decode(input.imageBase64());
                } catch (IllegalArgumentException exception) {
                    throw DomainException.invalid("Invalid photograph encoding.");
                }
                if (data.length > MAX_BYTES)
                    throw DomainException.invalid("Use a photograph smaller than 8 MB.");
            }
            return jpeg(data);
        } catch (IOException exception) {
            throw DomainException.invalid("The photograph could not be decoded.");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String jpeg(byte[] data) throws IOException {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext())
                throw DomainException.invalid("Use a JPEG, PNG or WebP photograph.");
            var reader = readers.next();
            try {
                if (!Set.of("jpeg", "png", "webp")
                        .contains(reader.getFormatName().toLowerCase(Locale.ROOT)))
                    throw DomainException.invalid("Use a JPEG, PNG or WebP photograph.");
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1
                        || height < 1
                        || width > 16384
                        || height > 16384
                        || (long) width * height > 16_777_216)
                    throw DomainException.invalid("Use a photograph with at most 16 megapixels.");
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, (Math.max(width, height) + 2047) / 2048);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                var decoded = reader.read(0, parameters);
                var rgb =
                        new BufferedImage(
                                decoded.getWidth(),
                                decoded.getHeight(),
                                BufferedImage.TYPE_INT_RGB);
                var graphics = rgb.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                    graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(decoded, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                var output = new ByteArrayOutputStream();
                var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                try (var destination = new MemoryCacheImageOutputStream(output)) {
                    writer.setOutput(destination);
                    var compression = writer.getDefaultWriteParam();
                    compression.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    compression.setCompressionQuality(0.9f);
                    writer.write(null, new IIOImage(rgb, null, null), compression);
                } finally {
                    writer.dispose();
                }
                return Base64.getEncoder().encodeToString(output.toByteArray());
            } finally {
                reader.dispose();
            }
        }
    }
}
