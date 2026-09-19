package com.closetos.activity.infrastructure;

import com.closetos.activity.application.ProcessingResults;
import com.closetos.activity.application.ProcessingTransitions;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.ProcessingAccess;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "closetos.media.aws-consumer-enabled", havingValue = "true")
class AwsMediaConsumer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AwsMediaConsumer.class);
    private final SqsClient sqs;
    private final JsonMapper json;
    private final MediaAccess media;
    private final ObjectStoragePort storage;
    private final ProcessingAccess processing;
    private final ProcessingTransitions transitions;
    private final ProcessingResults results;
    private final String ingestQueue;
    private final String resultQueue;
    private final String bucket;

    AwsMediaConsumer(
            JsonMapper json,
            MediaAccess media,
            ObjectStoragePort storage,
            ProcessingAccess processing,
            ProcessingTransitions transitions,
            ProcessingResults results,
            @Value("${closetos.aws.region:eu-west-2}") String region,
            @Value("${closetos.media.ingest-queue-url}") String ingestQueue,
            @Value("${closetos.media.result-queue-url}") String resultQueue,
            @Value("${closetos.media.bucket:closetos}") String bucket) {
        this.sqs = SqsClient.builder().region(Region.of(region)).build();
        this.json = json;
        this.media = media;
        this.storage = storage;
        this.processing = processing;
        this.transitions = transitions;
        this.results = results;
        this.ingestQueue = ingestQueue;
        this.resultQueue = resultQueue;
        this.bucket = bucket;
    }

    @Scheduled(fixedDelay = 1000)
    void ingest() {
        receive(ingestQueue, this::uploaded);
    }

    @Scheduled(fixedDelay = 1000)
    void results() {
        receive(resultQueue, this::completed);
    }

    private void receive(String queue, Consumer<JsonNode> handler) {
        try {
            for (var message :
                    sqs.receiveMessage(
                                    request ->
                                            request.queueUrl(queue)
                                                    .waitTimeSeconds(20)
                                                    .maxNumberOfMessages(5))
                            .messages()) {
                try {
                    handler.accept(json.readTree(message.body()));
                    sqs.deleteMessage(
                            request ->
                                    request.queueUrl(queue).receiptHandle(message.receiptHandle()));
                } catch (RuntimeException exception) {
                    LOG.error("Media message {} will be retried", message.messageId(), exception);
                }
            }
        } catch (RuntimeException exception) {
            LOG.error("Media queue receive failed", exception);
        }
    }

    private void uploaded(JsonNode event) {
        if (!"aws.s3".equals(event.path("source").asText())
                || !"Object Created".equals(event.path("detail-type").asText()))
            throw new IllegalArgumentException("Unsupported storage event");
        if (!bucket.equals(event.path("detail").path("bucket").path("name").asText()))
            throw new IllegalArgumentException("Unexpected bucket");
        String eventId = eventId(event, "id");
        var image = media.bySourceKey(event.path("detail").path("object").path("key").asText());
        if (image.isEmpty()) return;
        var object =
                storage.head(image.get().sourceS3Key())
                        .orElseThrow(() -> new IllegalStateException("Uploaded object missing"));
        if (object.size() != image.get().expectedSize()
                || !image.get().mimeType().equals(object.mimeType())
                || !image.get().sourceChecksum().equals(object.checksumSha256()))
            throw new IllegalArgumentException("Uploaded object failed verification");
        transitions.uploaded(image.get(), eventId);
    }

    private void completed(JsonNode event) {
        String eventId = eventId(event, "eventId");
        var context = processing.context(UUID.fromString(event.path("jobId").asText()));
        if (context.isEmpty()) return;
        String status = event.path("status").asText();
        if ("FAILED".equals(status)) {
            transitions.failed(
                    context.get().jobId(),
                    "PROCESSING_FAILURE",
                    "We could not process this photograph. Try again or add the details yourself.");
        } else if ("SUCCEEDED".equals(status)
                && context.get().manifestKey().equals(event.path("manifestKey").asText())) {
            results.manifest(context.get(), eventId);
        } else throw new IllegalArgumentException("Invalid processing result event");
    }

    private String eventId(JsonNode event, String field) {
        String value = event.path(field).asText();
        if (value.isBlank() || value.length() > 200)
            throw new IllegalArgumentException("Invalid event identifier");
        return value;
    }

    @Override
    public void close() {
        sqs.close();
    }
}
