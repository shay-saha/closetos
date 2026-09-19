package com.closetos.activity.infrastructure;

import com.closetos.activity.application.ProcessingTransitions;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.ObjectStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "closetos.media.local-upload-observer", havingValue = "true")
class LocalUploadObserver {
    private static final Logger LOG = LoggerFactory.getLogger(LocalUploadObserver.class);
    private final MediaAccess media;
    private final ObjectStoragePort storage;
    private final ProcessingTransitions transitions;

    LocalUploadObserver(
            MediaAccess media, ObjectStoragePort storage, ProcessingTransitions transitions) {
        this.media = media;
        this.storage = storage;
        this.transitions = transitions;
    }

    @Scheduled(fixedDelayString = "${closetos.media.upload-poll-ms:2000}")
    void observe() {
        for (var image : media.awaitingUploads()) {
            try {
                storage.head(image.sourceS3Key())
                        .ifPresent(
                                object -> {
                                    if (object.size() != image.expectedSize()
                                            || !image.mimeType().equals(object.mimeType())
                                            || !image.sourceChecksum()
                                                    .equals(object.checksumSha256())) {
                                        LOG.warn(
                                                "Uploaded image {} did not match its reservation",
                                                image.id());
                                        return;
                                    }
                                    transitions.uploaded(image, "local-upload:" + image.id());
                                });
            } catch (RuntimeException exception) {
                LOG.warn("Could not observe image {}", image.id(), exception);
            }
        }
    }
}
