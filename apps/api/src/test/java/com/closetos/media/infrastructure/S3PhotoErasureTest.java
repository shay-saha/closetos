package com.closetos.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.closetos.media.api.MediaErasurePending;
import com.closetos.platform.api.DomainException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;

class S3PhotoErasureTest {
    private final UUID owner = UUID.randomUUID();
    private final String prefix =
            "users/"
                    + owner
                    + "/garments/"
                    + UUID.randomUUID()
                    + "/images/"
                    + UUID.randomUUID()
                    + "/";
    private final String original = prefix + "original.png";
    private final S3Client s3 = mock(S3Client.class);
    private final S3PhotoErasure erasure = new S3PhotoErasure(s3, "media");

    @BeforeEach
    void paginatorUsesActualPageRequests() {
        when(s3.listObjectVersionsPaginator(any(ListObjectVersionsRequest.class)))
                .thenAnswer(call -> new ListObjectVersionsIterable(s3, call.getArgument(0)));
    }

    @Test
    void replacesTheOriginalConditionallyAndDeletesOnlyItsHistoricalVersionsAndMarkers() {
        var erased = erased();
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(
                        HeadObjectResponse.builder()
                                .contentLength(10L)
                                .eTag("photo-etag")
                                .versionId("photo-version")
                                .build(),
                        erased);
        when(s3.putObject(
                        any(PutObjectRequest.class),
                        any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("guard").build());
        var photo = version(original, "photo-version", 10, false);
        var guard = version(original, "guard", 0, true);
        var neighbor = version(original + ".backup", "neighbor", 10, true);
        var before =
                ListObjectVersionsResponse.builder()
                        .versions(guard, photo, neighbor)
                        .deleteMarkers(
                                DeleteMarkerEntry.builder()
                                        .key(original)
                                        .versionId("deleted-photo")
                                        .isLatest(false)
                                        .build())
                        .build();
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(
                        before,
                        before,
                        ListObjectVersionsResponse.builder().versions(guard, neighbor).build());
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());
        erasure.original(original);
        var replacement = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3)
                .putObject(
                        replacement.capture(),
                        any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(replacement.getValue().ifMatch()).isEqualTo("photo-etag");
        assertThat(replacement.getValue().ifNoneMatch()).isNull();
        assertThat(replacement.getValue().metadata()).containsEntry("closetos-erased", "1");
        var deletion = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3).deleteObjects(deletion.capture());
        assertThat(deletion.getValue().delete().objects())
                .extracting(ObjectIdentifier::versionId)
                .containsExactlyInAnyOrder("photo-version", "deleted-photo");
        assertThat(deletion.getValue().delete().objects())
                .allMatch(object -> object.key().equals(original));
    }

    @Test
    void validatesAllPreparedSourcesBeforeTouchingStorage() {
        assertThatThrownBy(
                        () ->
                                erasure.owner(
                                        owner,
                                        List.of(
                                                original,
                                                original.replace(
                                                        owner.toString(),
                                                        UUID.randomUUID().toString()))))
                .isInstanceOf(DomainException.class);
        for (String key :
                List.of(
                        prefix + "../original.png",
                        prefix + "pipelines/1-r1/card.webp",
                        "users/------------------------------------/garments/"
                                + UUID.randomUUID()
                                + "/images/"
                                + UUID.randomUUID()
                                + "/original.png"))
            assertThatThrownBy(() -> erasure.original(key)).isInstanceOf(DomainException.class);
        verifyNoInteractions(s3);
    }

    @Test
    void rejectsAForeignKeyInAnyPageBeforeWritingOrDeletingIt() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(erased());
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(
                        ListObjectVersionsResponse.builder()
                                .versions(version(original, "ours", 10, true))
                                .deleteMarkers(
                                        DeleteMarkerEntry.builder()
                                                .key("users/another-owner/private")
                                                .versionId("foreign")
                                                .build())
                                .build());
        assertThatThrownBy(() -> erasure.original(original))
                .isInstanceOf(MediaErasurePending.class);
        verify(s3, never())
                .putObject(
                        any(PutObjectRequest.class),
                        any(software.amazon.awssdk.core.sync.RequestBody.class));
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void refusesToDeleteAnObjectWhoseVersionWasNotIdentified(String versionId) {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(erased());
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(
                        ListObjectVersionsResponse.builder()
                                .versions(version(original, versionId, 10, false))
                                .build());
        assertThatThrownBy(() -> erasure.original(original))
                .isInstanceOf(MediaErasurePending.class);
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void aChangedOrUnidentifiedCurrentVersionCannotCauseTheGuardToBeDeleted() {
        for (var version :
                List.of(
                        version(original, "different-guard", 0, true),
                        ObjectVersion.builder().key(original).versionId("old").size(10L).build(),
                        version(original, "guard", 0, false))) {
            reset(s3);
            paginatorUsesActualPageRequests();
            when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(erased());
            when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                    .thenReturn(ListObjectVersionsResponse.builder().versions(version).build());
            assertThatThrownBy(() -> erasure.original(original))
                    .isInstanceOf(MediaErasurePending.class);
            verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
        }
    }

    @Test
    void partialDeletionErrorsKeepErasurePending() {
        pendingVersions();
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(
                        DeleteObjectsResponse.builder()
                                .errors(
                                        S3Error.builder()
                                                .key(original)
                                                .versionId("old")
                                                .code("AccessDenied")
                                                .build())
                                .build());
        assertThatThrownBy(() -> erasure.original(original))
                .isInstanceOf(MediaErasurePending.class);
    }

    @Test
    void aQuietSuccessfulResponseDoesNotProveThatThePhotoWasErased() {
        pendingVersions();
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());
        assertThatThrownBy(() -> erasure.original(original))
                .isInstanceOf(MediaErasurePending.class);
        verify(s3).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 412, 403, 503})
    void aRejectedGuardCannotAcknowledgeErasure(int status) {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        when(s3.putObject(
                        any(PutObjectRequest.class),
                        any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenThrow(
                        S3Exception.builder()
                                .statusCode(status)
                                .message("private object details")
                                .build());
        assertThatThrownBy(() -> erasure.original(original))
                .isInstanceOf(MediaErasurePending.class)
                .hasMessage("Photo cleanup awaits storage confirmation.")
                .hasNoCause();
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3)
                .putObject(
                        request.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().ifMatch()).isNull();
    }

    @Test
    void timeoutsExposeNeitherPrivateKeysNorAnUnverifiedSuccess() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.create("private object details"));
        assertThatThrownBy(() -> erasure.original(original))
                .isInstanceOf(MediaErasurePending.class)
                .hasMessage("Photo cleanup awaits storage confirmation.")
                .hasNoCause();
    }

    @Test
    void anUnconfirmedGuardOrItsDisappearanceDoesNotPermitCleanupToComplete() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(
                        HeadObjectResponse.builder()
                                .contentLength(10L)
                                .eTag("photo-etag")
                                .versionId("old")
                                .build());
        when(s3.putObject(
                        any(PutObjectRequest.class),
                        any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("guard").build());
        assertThatThrownBy(() -> erasure.original(original))
                .isInstanceOf(MediaErasurePending.class);
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
        reset(s3);
        paginatorUsesActualPageRequests();
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(erased())
                .thenThrow(S3Exception.builder().statusCode(404).build());
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(
                        ListObjectVersionsResponse.builder()
                                .versions(version(original, "guard", 0, true))
                                .build());
        assertThatThrownBy(() -> erasure.original(original))
                .isInstanceOf(MediaErasurePending.class);
    }

    private void pendingVersions() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(erased());
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(
                        ListObjectVersionsResponse.builder()
                                .versions(
                                        version(original, "guard", 0, true),
                                        version(original, "old", 10, false))
                                .build());
    }

    private HeadObjectResponse erased() {
        return HeadObjectResponse.builder()
                .contentLength(0L)
                .versionId("guard")
                .metadata(Map.of("closetos-erased", "1"))
                .build();
    }

    private ObjectVersion version(String key, String id, long size, boolean latest) {
        return ObjectVersion.builder().key(key).versionId(id).size(size).isLatest(latest).build();
    }
}
