package com.closetos.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.closetos.media.api.MediaErasurePending;
import com.closetos.platform.api.DomainException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationRequest;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationResponse;
import software.amazon.awssdk.services.cloudfront.model.GetInvalidationRequest;
import software.amazon.awssdk.services.cloudfront.model.GetInvalidationResponse;
import software.amazon.awssdk.services.cloudfront.model.Invalidation;
import software.amazon.awssdk.services.cloudfront.model.InvalidationBatch;

class CloudFrontPhotoErasureTest {
    private final CloudFrontClient client = mock(CloudFrontClient.class);
    private final CloudFrontPhotoErasure erasure = new CloudFrontPhotoErasure(client, "EMEDIA123");
    private final UUID request = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @Test
    void requestAcceptanceDoesNotProveCompletionAndRetriesReuseTheSameBatch() {
        when(client.createInvalidation(any(CreateInvalidationRequest.class)))
                .thenReturn(
                        CreateInvalidationResponse.builder()
                                .invalidation(invalidation("Completed"))
                                .build());
        when(client.getInvalidation(any(GetInvalidationRequest.class)))
                .thenReturn(
                        GetInvalidationResponse.builder()
                                .invalidation(invalidation("InProgress"))
                                .build(),
                        GetInvalidationResponse.builder()
                                .invalidation(invalidation("Completed"))
                                .build());
        assertThat(erasure.erase(request, owner)).isFalse();
        assertThat(erasure.erase(request, owner)).isTrue();
        var creations = ArgumentCaptor.forClass(CreateInvalidationRequest.class);
        verify(client, org.mockito.Mockito.times(2)).createInvalidation(creations.capture());
        assertThat(creations.getAllValues())
                .allSatisfy(
                        value -> {
                            assertThat(value.distributionId()).isEqualTo("EMEDIA123");
                            assertThat(value.invalidationBatch().callerReference())
                                    .isEqualTo("account-removal-" + request);
                            assertThat(value.invalidationBatch().paths().quantity()).isEqualTo(1);
                            assertThat(value.invalidationBatch().paths().items())
                                    .containsExactly("/users/" + owner + "/*");
                        });
        var observation = ArgumentCaptor.forClass(GetInvalidationRequest.class);
        verify(client, org.mockito.Mockito.times(2)).getInvalidation(observation.capture());
        assertThat(observation.getAllValues())
                .allSatisfy(
                        value -> {
                            assertThat(value.distributionId()).isEqualTo("EMEDIA123");
                            assertThat(value.id()).isEqualTo("IERASURE123");
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "absent",
                "blank-id",
                "different-id",
                "different-request",
                "different-owner",
                "global-path",
                "extra-path",
                "wrong-quantity",
                "absent-paths",
                "absent-batch",
                "absent-status",
                "unknown-status"
            })
    void completedResponsesRequireTheExactInvalidationAndAccountScope(String problem) {
        when(client.createInvalidation(any(CreateInvalidationRequest.class)))
                .thenReturn(
                        CreateInvalidationResponse.builder()
                                .invalidation(invalidation("InProgress"))
                                .build());
        var result = invalidation("Completed").toBuilder();
        switch (problem) {
            case "blank-id" -> result.id(" ");
            case "different-id" -> result.id("IOTHER");
            case "different-request" ->
                    result.invalidationBatch(
                            batch().toBuilder().callerReference("other-request").build());
            case "different-owner" ->
                    result.invalidationBatch(
                            batch().toBuilder()
                                    .paths(
                                            paths ->
                                                    paths.quantity(1)
                                                            .items(
                                                                    "/users/"
                                                                            + UUID.randomUUID()
                                                                            + "/*"))
                                    .build());
            case "global-path" ->
                    result.invalidationBatch(
                            batch().toBuilder()
                                    .paths(paths -> paths.quantity(1).items("/*"))
                                    .build());
            case "extra-path" ->
                    result.invalidationBatch(
                            batch().toBuilder()
                                    .paths(
                                            paths ->
                                                    paths.quantity(2)
                                                            .items("/users/" + owner + "/*", "/*"))
                                    .build());
            case "wrong-quantity" ->
                    result.invalidationBatch(
                            batch().toBuilder()
                                    .paths(
                                            paths ->
                                                    paths.quantity(0)
                                                            .items("/users/" + owner + "/*"))
                                    .build());
            case "absent-paths" ->
                    result.invalidationBatch(
                            InvalidationBatch.builder()
                                    .callerReference(batch().callerReference())
                                    .build());
            case "absent-batch" -> result.invalidationBatch((InvalidationBatch) null);
            case "absent-status" -> result.status((String) null);
            case "unknown-status" -> result.status("Successful");
            default -> {}
        }
        when(client.getInvalidation(any(GetInvalidationRequest.class)))
                .thenReturn(
                        GetInvalidationResponse.builder()
                                .invalidation("absent".equals(problem) ? null : result.build())
                                .build());
        assertThatThrownBy(() -> erasure.erase(request, owner))
                .isInstanceOf(MediaErasurePending.class);
    }

    @Test
    void missingCreateConfirmationCannotStartAnObservation() {
        when(client.createInvalidation(any(CreateInvalidationRequest.class)))
                .thenReturn(CreateInvalidationResponse.builder().build());
        assertThatThrownBy(() -> erasure.erase(request, owner))
                .isInstanceOf(MediaErasurePending.class);
        verify(client, org.mockito.Mockito.never())
                .getInvalidation(any(GetInvalidationRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 429, 503})
    void providerErrorsRemainRetryableWithoutPrivateDiagnostics(int status) {
        when(client.createInvalidation(any(CreateInvalidationRequest.class)))
                .thenThrow(
                        CloudFrontException.builder()
                                .statusCode(status)
                                .message("private distribution /users/" + owner)
                                .build());
        assertThatThrownBy(() -> erasure.erase(request, owner))
                .isInstanceOf(MediaErasurePending.class)
                .hasNoCause()
                .hasMessage("Photo cleanup awaits storage confirmation.");
    }

    @Test
    void lostStatusConfirmationIsNotAcknowledged() {
        when(client.createInvalidation(any(CreateInvalidationRequest.class)))
                .thenReturn(
                        CreateInvalidationResponse.builder()
                                .invalidation(invalidation("InProgress"))
                                .build());
        when(client.getInvalidation(any(GetInvalidationRequest.class)))
                .thenThrow(SdkClientException.create("private timeout"));
        assertThatThrownBy(() -> erasure.erase(request, owner))
                .isInstanceOf(MediaErasurePending.class)
                .hasNoCause();
    }

    @Test
    void invalidScopeOrDistributionCannotReachTheProvider() {
        assertThatThrownBy(() -> erasure.erase(null, owner)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> erasure.erase(request, null)).isInstanceOf(DomainException.class);
        for (String id : List.of("", " ", "EMEDIA/other", "https://cdn.example", "*", "emedia"))
            assertThatThrownBy(() -> new CloudFrontPhotoErasure(client, id))
                    .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client);
    }

    private Invalidation invalidation(String status) {
        return Invalidation.builder()
                .id("IERASURE123")
                .status(status)
                .invalidationBatch(batch())
                .build();
    }

    private InvalidationBatch batch() {
        return InvalidationBatch.builder()
                .callerReference("account-removal-" + request)
                .paths(paths -> paths.quantity(1).items("/users/" + owner + "/*"))
                .build();
    }
}
