package com.closetos.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort.Input;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.databind.json.JsonMapper;

class BedrockEmbeddingClientTest {
    private static final String MODEL =
            "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1";
    private final BedrockRuntimeClient runtime = mock(BedrockRuntimeClient.class);
    private final S3Client storage = mock(S3Client.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final BedrockEmbeddingClient provider =
            new BedrockEmbeddingClient(runtime, storage, json, "media", MODEL, 256);

    @ParameterizedTest
    @ValueSource(ints = {256, 384, 1024})
    void sendsTheTitanContractAndNormalizesAllSupportedVectorSizes(int dimensions) {
        var configured =
                new BedrockEmbeddingClient(runtime, storage, json, "media", MODEL, dimensions);
        when(runtime.invokeModel(any(InvokeModelRequest.class))).thenReturn(vector(dimensions));
        var result =
                configured.embed(
                        new Input("blue summer shirt", null, null, null), configured.model());
        var request = ArgumentCaptor.forClass(InvokeModelRequest.class);
        org.mockito.Mockito.verify(runtime).invokeModel(request.capture());
        var body = json.readTree(request.getValue().body().asUtf8String());
        assertThat(request.getValue().modelId()).isEqualTo(MODEL);
        assertThat(request.getValue().accept()).isEqualTo("application/json");
        assertThat(request.getValue().contentType()).isEqualTo("application/json");
        assertThat(body.get("inputText").asText()).isEqualTo("blue summer shirt");
        assertThat(body.has("inputImage")).isFalse();
        assertThat(body.get("embeddingConfig").get("outputEmbeddingLength").asInt())
                .isEqualTo(dimensions);
        assertThat(result.modelKey()).isEqualTo(configured.model().modelKey());
        assertThat(result.vector())
                .hasSize(dimensions)
                .allSatisfy(
                        value ->
                                assertThat(value)
                                        .isCloseTo(
                                                1 / Math.sqrt(dimensions),
                                                org.assertj.core.data.Offset.offset(1e-12)));
        verifyNoInteractions(storage);
    }

    @Test
    void convertsRealTransparentWebpDerivativesToWhiteBackgroundJpeg() throws Exception {
        byte[] photograph;
        try (var fixture = getClass().getResourceAsStream("/embedding/garment.webp")) {
            photograph = fixture.readAllBytes();
        }
        String key =
                "users/00000000-0000-4000-8000-000000000001/garments/00000000-0000-4000-8000-000000000002/images/00000000-0000-4000-8000-000000000003/pipelines/1-r1/display.webp";
        String checksum =
                Base64.getEncoder()
                        .encodeToString(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(photograph));
        when(storage.getObject(any(GetObjectRequest.class)))
                .thenReturn(
                        new ResponseInputStream<>(
                                GetObjectResponse.builder()
                                        .contentLength((long) photograph.length)
                                        .build(),
                                new ByteArrayInputStream(photograph)));
        when(runtime.invokeModel(any(InvokeModelRequest.class)))
                .thenAnswer(
                        call -> {
                            var body =
                                    json.readTree(
                                            call.getArgument(0, InvokeModelRequest.class)
                                                    .body()
                                                    .asUtf8String());
                            assertThat(body.get("inputText").asText()).isEqualTo("blue shirt");
                            var bytes = Base64.getDecoder().decode(body.get("inputImage").asText());
                            assertThat(bytes[0]).isEqualTo((byte) 0xff);
                            assertThat(bytes[1]).isEqualTo((byte) 0xd8);
                            var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
                            assertThat(decoded.getWidth()).isEqualTo(64);
                            assertThat(decoded.getHeight()).isEqualTo(48);
                            assertThat(new java.awt.Color(decoded.getRGB(1, 1)).getRed())
                                    .isGreaterThan(240);
                            assertThat(new java.awt.Color(decoded.getRGB(32, 24)).getBlue())
                                    .isGreaterThan(150);
                            return vector(256);
                        });
        provider.embed(new Input("blue shirt", key, checksum, null), provider.model());
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        org.mockito.Mockito.verify(storage).getObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("media");
        assertThat(request.getValue().key()).isEqualTo(key);
    }

    @Test
    void imageQueriesDoNotReadStoredWardrobeObjects() throws Exception {
        var image =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        when(runtime.invokeModel(any(InvokeModelRequest.class))).thenReturn(vector(256));
        assertThat(
                        provider.embed(
                                        new Input(
                                                null,
                                                null,
                                                null,
                                                Base64.getEncoder()
                                                        .encodeToString(bytes.toByteArray())),
                                        provider.model())
                                .vector())
                .hasSize(256);
        verifyNoInteractions(storage);
    }

    @Test
    void identitiesAreStableAndIsolateDimensionChanges() {
        var same = new BedrockEmbeddingClient(runtime, storage, json, "other-bucket", MODEL, 256);
        var larger = new BedrockEmbeddingClient(runtime, storage, json, "media", MODEL, 1024);
        assertThat(same.model()).isEqualTo(provider.model());
        assertThat(larger.model().modelKey()).isNotEqualTo(provider.model().modelKey());
        assertThat(provider.model().model().pipelineVersion()).isEqualTo("titan-jpeg-1");
        assertThatThrownBy(
                        () -> provider.embed(new Input("shirt", null, null, null), larger.model()))
                .isInstanceOf(DomainException.class)
                .extracting("status")
                .isEqualTo(409);
        assertThatThrownBy(
                        () ->
                                provider.embed(
                                        new Input("shirt", null, null, null),
                                        new ModelInfo("0".repeat(64), provider.model().model())))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(runtime, storage);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "amazon.titan-embed-text-v2:0",
                "arn:aws:bedrock:us-east-1:123456789012:foundation-model/amazon.titan-embed-image-v1"
            })
    void rejectsIncompatibleModelsAtStartup(String model) {
        assertThatThrownBy(
                        () ->
                                new BedrockEmbeddingClient(
                                        runtime, storage, json, "media", model, 256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidInputsBeforeMakingPaidCalls() {
        for (Input input :
                new Input[] {
                    null,
                    new Input(null, null, null, null),
                    new Input(" ", null, null, null),
                    new Input("x".repeat(1001), null, null, null),
                    new Input(null, null, null, "invalid"),
                    new Input(null, "original.png", "x".repeat(44), null),
                    new Input(null, "display.webp", null, null),
                    new Input("shirt", null, "x".repeat(44), null)
                }) {
            assertThatThrownBy(() -> provider.embed(input, provider.model()))
                    .isInstanceOf(DomainException.class);
        }
        verifyNoInteractions(runtime, storage);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"embedding\":[]}",
                "{\"embedding\":[1,2,3]}",
                "{\"message\":\"failed\"}",
                "{\"embedding\":null}",
                "[]"
            })
    void rejectsMalformedModelResponses(String body) {
        when(runtime.invokeModel(any(InvokeModelRequest.class))).thenReturn(response(body));
        assertThatThrownBy(
                        () ->
                                provider.embed(
                                        new Input("shirt", null, null, null), provider.model()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidCoordinatesAndOversizedResponses() {
        for (Object value : new Object[] {0, "1", true, null}) {
            var body = json.createObjectNode();
            body.set("embedding", json.valueToTree(Collections.nCopies(256, value)));
            when(runtime.invokeModel(any(InvokeModelRequest.class)))
                    .thenReturn(response(json.writeValueAsString(body)));
            assertThatThrownBy(
                            () ->
                                    provider.embed(
                                            new Input("shirt", null, null, null), provider.model()))
                    .isInstanceOf(RuntimeException.class);
        }
        when(runtime.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(response(" ".repeat(131_073)));
        assertThatThrownBy(
                        () ->
                                provider.embed(
                                        new Input("shirt", null, null, null), provider.model()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void releasesCapacityAfterProviderFailuresAndKeepsCloudMessagesPrivate() {
        when(runtime.invokeModel(any(InvokeModelRequest.class)))
                .thenThrow(ThrottlingException.builder().message("private cloud context").build())
                .thenThrow(
                        ValidationException.builder().message("private photograph details").build())
                .thenReturn(vector(256));
        assertThatThrownBy(
                        () ->
                                provider.embed(
                                        new Input("shirt", null, null, null), provider.model()))
                .isInstanceOf(DomainException.class)
                .extracting("status", "message")
                .containsExactly(503, "Semantic search is temporarily unavailable.");
        assertThatThrownBy(
                        () ->
                                provider.embed(
                                        new Input("shirt", null, null, null), provider.model()))
                .isInstanceOf(DomainException.class)
                .extracting("status", "message")
                .containsExactly(400, "The text or photograph could not be embedded.");
        assertThat(provider.embed(new Input("shirt", null, null, null), provider.model()).vector())
                .hasSize(256);
    }

    @Test
    void limitsConcurrentInferenceWithoutCreatingAnUnboundedWaitingQueue() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(runtime.invokeModel(any(InvokeModelRequest.class)))
                .thenAnswer(
                        call -> {
                            started.countDown();
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            return vector(256);
                        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () ->
                                    provider.embed(
                                            new Input("shirt", null, null, null),
                                            provider.model()));
            var second =
                    executor.submit(
                            () ->
                                    provider.embed(
                                            new Input("dress", null, null, null),
                                            provider.model()));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(
                                () ->
                                        provider.embed(
                                                new Input("skirt", null, null, null),
                                                provider.model()))
                        .isInstanceOf(DomainException.class)
                        .extracting("status")
                        .isEqualTo(429);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).vector()).hasSize(256);
            assertThat(second.get(5, TimeUnit.SECONDS).vector()).hasSize(256);
        }
    }

    private InvokeModelResponse vector(int dimensions) {
        return response(
                json.writeValueAsString(
                        java.util.Map.of("embedding", Collections.nCopies(dimensions, 2))));
    }

    private InvokeModelResponse response(String body) {
        return InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(body))
                .contentType("application/json")
                .build();
    }
}
