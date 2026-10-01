package com.closetos.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.closetos.media.api.ImageRecord;
import com.closetos.media.api.ImageRole;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.MediaSigningAdmission;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class MediaStorageAdmissionTest {
    private final UUID owner = UUID.randomUUID(),
            garment = UUID.randomUUID(),
            image = UUID.randomUUID();
    private final String prefix =
            "users/" + owner + "/garments/" + garment + "/images/" + image + "/";
    private final Instant now = Instant.now();
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final S3Client s3 = mock(S3Client.class);
    private final S3Presigner signer = mock(S3Presigner.class);
    private final MediaDownloadSigner downloads = mock(MediaDownloadSigner.class);
    private final MediaSigningAdmission admission = mock(MediaSigningAdmission.class);

    @Test
    void deniedAdmissionCannotMintUploadDisplayOrExportLinks() {
        when(admission.sign(any(), any()))
                .thenThrow(new DomainException(401, "ACCOUNT_REMOVED", "Account removed"));
        var storage = storage(signer);
        var exports = new S3PersonalPhotoDownloads(signer, clock, "media", admission);
        assertThatThrownBy(() -> storage.signUpload(image(prefix + "original.png")))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                storage.signDownload(
                                        prefix + "pipelines/1-r1/card.webp", now.plusSeconds(900)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                exports.sign(
                                        owner,
                                        garment,
                                        image,
                                        prefix + "original.png",
                                        now.plusSeconds(900)))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(s3, signer, downloads);
    }

    @Test
    void uploadAndDeliveryAdmissionUseTheOwnerOfTheActualStoredPhoto() {
        doAnswer(call -> call.<Supplier<?>>getArgument(1).get()).when(admission).sign(any(), any());
        try (var realSigner =
                S3Presigner.builder()
                        .region(Region.EU_WEST_2)
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create("test-access", "test-secret")))
                        .build()) {
            var upload = storage(realSigner).signUpload(image(prefix + "original.png"));
            assertThat(URI.create(upload.url()).getPath()).isEqualTo("/" + prefix + "original.png");
            assertThat(upload.url()).contains("X-Amz-Expires=600");
            assertThat(upload.expiresAt()).isEqualTo(now.plusSeconds(600));
            verify(admission).sign(eq(owner), any());
        }
        String card = prefix + "pipelines/1-r1/card.webp";
        when(downloads.sign(card, now.plusSeconds(900))).thenReturn("private-card-link");
        assertThat(storage(signer).signDownload(card, now.plusSeconds(900)))
                .isEqualTo("private-card-link");
        verify(downloads).sign(card, now.plusSeconds(900));
        verifyNoInteractions(s3);
    }

    @Test
    void crossOwnerUploadsTraversalDiagnosticsAndOverlongDownloadsCannotBeSigned() {
        doAnswer(call -> call.<Supplier<?>>getArgument(1).get()).when(admission).sign(any(), any());
        var storage = storage(signer);
        assertThatThrownBy(
                        () ->
                                storage.signUpload(
                                        image(
                                                prefix.replace(
                                                                owner.toString(),
                                                                UUID.randomUUID().toString())
                                                        + "original.png")))
                .isInstanceOf(DomainException.class);
        for (String key :
                new String[] {
                    prefix + "../original.png",
                    prefix + "pipelines/1-r1/analysis.json",
                    "users/------------------------------------/garments/"
                            + garment
                            + "/images/"
                            + image
                            + "/original.png",
                    null
                })
            assertThatThrownBy(() -> storage.signDownload(key, now.plusSeconds(900)))
                    .isInstanceOf(DomainException.class);
        for (Instant expiry : new Instant[] {null, now, now.minusSeconds(1), now.plusSeconds(901)})
            assertThatThrownBy(
                            () -> storage.signDownload(prefix + "pipelines/1-r1/card.webp", expiry))
                    .isInstanceOf(DomainException.class);
        verifyNoInteractions(s3, signer, downloads);
    }

    private S3ObjectStorage storage(S3Presigner presigner) {
        return new S3ObjectStorage(s3, presigner, downloads, clock, "media", admission);
    }

    private ImageRecord image(String key) {
        return new ImageRecord(
                image,
                garment,
                UUID.randomUUID(),
                owner,
                key,
                "image/png",
                10,
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                ProcessingStatus.AWAITING_UPLOAD,
                null,
                false,
                "photo.png",
                ImageRole.FRONT);
    }
}
