package com.closetos.search.infrastructure;

import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingModel;
import com.closetos.search.api.EmbeddingProviderPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

final class BedrockEmbeddingClient implements EmbeddingProviderPort {
    private final BedrockRuntimeClient client;
    private final JsonMapper json;
    private final BedrockEmbeddingImages images;
    private final ModelInfo identity;
    private final Semaphore inference = new Semaphore(2);

    BedrockEmbeddingClient(
            BedrockRuntimeClient client,
            S3Client storage,
            JsonMapper json,
            String bucket,
            String modelId,
            int dimensions) {
        if (modelId == null
                || !modelId.matches(
                        "(?:arn:aws(?:-[a-z]+)?:bedrock:[a-z0-9-]+::foundation-model/)?amazon\\.titan-embed-image-v1"))
            throw new IllegalArgumentException(
                    "Configure the Titan multimodal embedding model ID or ARN.");
        var model = new EmbeddingModel("bedrock", modelId, "v1", "titan-jpeg-1", dimensions);
        // Canonical field order keeps model identity stable across processes and releases.
        var descriptor = json.createObjectNode();
        descriptor.put("dimensions", dimensions);
        descriptor.put("modelId", model.modelId());
        descriptor.put("modelVersion", model.modelVersion());
        descriptor.put("pipelineVersion", model.pipelineVersion());
        descriptor.put("provider", model.provider());
        try {
            this.identity =
                    new ModelInfo(
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            json.writeValueAsString(descriptor)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8))),
                            model);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        this.client = client;
        this.json = json;
        this.images = new BedrockEmbeddingImages(storage, bucket);
    }

    @Override
    public ModelInfo model() {
        return identity;
    }

    @Override
    public VectorResult embed(Input input, ModelInfo expected) {
        if (!identity.equals(expected)) throw DomainException.conflict();
        if (input == null
                || input.text() == null && input.imageKey() == null && input.imageBase64() == null)
            throw DomainException.invalid("Provide text or a photograph for semantic search.");
        if (input.text() != null && (input.text().isBlank() || input.text().length() > 1000))
            throw DomainException.invalid("Use 1–1,000 characters for semantic search.");
        if (!inference.tryAcquire())
            throw new DomainException(
                    429, "EMBEDDING_BUSY", "Semantic search is busy. Try again shortly.");
        try {
            var body = json.createObjectNode();
            body.putObject("embeddingConfig")
                    .put("outputEmbeddingLength", identity.model().dimensions());
            if (input.text() != null) body.put("inputText", input.text());
            String image = images.prepare(input);
            if (image != null) body.put("inputImage", image);
            var response =
                    client.invokeModel(
                            InvokeModelRequest.builder()
                                    .modelId(identity.model().modelId())
                                    .contentType("application/json")
                                    .accept("application/json")
                                    .body(SdkBytes.fromUtf8String(json.writeValueAsString(body)))
                                    .build());
            var encoded = response.body().asByteArray();
            if (encoded.length > 131_072)
                throw new IllegalStateException("Embedding response exceeds its size limit.");
            var result = json.readTree(encoded);
            if (!result.isObject()
                    || result.hasNonNull("message") && !result.get("message").asText().isBlank())
                throw new IllegalStateException("Bedrock did not return an embedding.");
            var vector = result.get("embedding");
            if (vector == null
                    || !vector.isArray()
                    || vector.size() != identity.model().dimensions())
                throw new IllegalStateException("Embedding has the wrong dimensions.");
            var coordinates = new ArrayList<Double>(vector.size());
            for (var coordinate : vector) {
                if (!coordinate.isNumber())
                    throw new IllegalStateException("Embedding must contain numbers.");
                coordinates.add(coordinate.asDouble());
            }
            return new VectorResult(identity.modelKey(), identity.model(), coordinates);
        } catch (ValidationException exception) {
            throw DomainException.invalid("The text or photograph could not be embedded.");
        } catch (SdkException exception) {
            throw new DomainException(
                    503, "EMBEDDINGS_UNAVAILABLE", "Semantic search is temporarily unavailable.");
        } finally {
            inference.release();
        }
    }
}
