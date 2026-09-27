package com.closetos.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort.Input;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

class BedrockEmbeddingImagesTest {
    private static final String KEY =
            "users/00000000-0000-4000-8000-000000000001/garments/00000000-0000-4000-8000-000000000002/images/00000000-0000-4000-8000-000000000003/pipelines/1-r1/card.webp";
    private final S3Client storage = mock(S3Client.class);
    private final BedrockEmbeddingImages images = new BedrockEmbeddingImages(storage, "media");

    @Test
    void rejectsStoredImagesThatDoNotMatchTheirVerifiedChecksum() throws Exception {
        var data = image("png", 8, 8);
        when(storage.getObject(any(GetObjectRequest.class))).thenReturn(stream(data, data.length));
        assertThatThrownBy(
                        () ->
                                images.prepare(
                                        new Input(
                                                null,
                                                KEY,
                                                Base64.getEncoder().encodeToString(new byte[32]),
                                                null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsOversizedStoredImagesFromHeadersBeforeReadingTheBody() {
        when(storage.getObject(any(GetObjectRequest.class)))
                .thenReturn(stream(new byte[0], BedrockEmbeddingImages.MAX_BYTES + 1));
        assertThatThrownBy(
                        () ->
                                images.prepare(
                                        new Input(
                                                null,
                                                KEY,
                                                Base64.getEncoder().encodeToString(new byte[32]),
                                                null)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("8 MB");
    }

    @Test
    void enforcesTheBodyLimitEvenWhenAStoredObjectsLengthIsIncorrect() {
        when(storage.getObject(any(GetObjectRequest.class)))
                .thenReturn(stream(new byte[BedrockEmbeddingImages.MAX_BYTES + 1], 1));
        assertThatThrownBy(
                        () ->
                                images.prepare(
                                        new Input(
                                                null,
                                                KEY,
                                                Base64.getEncoder().encodeToString(new byte[32]),
                                                null)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("8 MB");
    }

    @Test
    void requiresARealSha256ChecksumBeforeReadingStorage() {
        for (String checksum : new String[] {"x", "!".repeat(44), "a".repeat(44)}) {
            assertThatThrownBy(() -> images.prepare(new Input(null, KEY, checksum, null)))
                    .isInstanceOf(DomainException.class);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void neverReadsOriginalsAnalysisFilesOrTraversalPaths() {
        String checksum = Base64.getEncoder().encodeToString(new byte[32]);
        for (String key :
                new String[] {
                    KEY.replace("card.webp", "original.png"),
                    KEY.replace("card.webp", "analysis.json"),
                    KEY.replace("card.webp", "../card.webp"),
                    KEY.replace("users/", "users/../"),
                    "https://example.test/card.webp"
                }) {
            assertThatThrownBy(() -> images.prepare(new Input(null, key, checksum, null)))
                    .isInstanceOf(DomainException.class);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsDecompressionBombDimensionsBeforeDecodingPixels() throws Exception {
        byte[] png = image("png", 8, 8);
        ByteBuffer.wrap(png).putInt(16, 16384).putInt(20, 16384);
        var crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        assertThatThrownBy(() -> images.prepare(query(png)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("16 megapixels");
    }

    @Test
    void rejectsUnsupportedImagesAndOversizedQueries() throws Exception {
        assertThatThrownBy(() -> images.prepare(query(image("gif", 8, 8))))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("JPEG, PNG or WebP");
        assertThatThrownBy(
                        () -> images.prepare(query(new byte[BedrockEmbeddingImages.MAX_BYTES + 1])))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> images.prepare(query(new byte[0])))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void downsamplesWithinTitansResolutionLimit() throws Exception {
        var jpeg = Base64.getDecoder().decode(images.prepare(query(image("png", 4096, 512))));
        var decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(decoded.getWidth()).isEqualTo(2048);
        assertThat(decoded.getHeight()).isEqualTo(256);
    }

    @Test
    void verifiesChecksumsOverTheOriginalBytesBeforeTranscoding() throws Exception {
        var data = image("png", 8, 8);
        var checksum =
                Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
        when(storage.getObject(any(GetObjectRequest.class))).thenReturn(stream(data, data.length));
        var jpeg = Base64.getDecoder().decode(images.prepare(new Input(null, KEY, checksum, null)));
        assertThat(ImageIO.read(new ByteArrayInputStream(jpeg)).getWidth()).isEqualTo(8);
    }

    private Input query(byte[] data) {
        return new Input(null, null, null, Base64.getEncoder().encodeToString(data));
    }

    private byte[] image(String format, int width, int height) throws Exception {
        var bytes = new ByteArrayOutputStream();
        assertThat(
                        ImageIO.write(
                                new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB),
                                format,
                                bytes))
                .isTrue();
        return bytes.toByteArray();
    }

    private ResponseInputStream<GetObjectResponse> stream(byte[] bytes, long length) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(length).build(),
                new ByteArrayInputStream(bytes));
    }
}
