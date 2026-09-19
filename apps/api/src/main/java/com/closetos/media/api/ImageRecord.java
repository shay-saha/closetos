package com.closetos.media.api;

import java.util.UUID;

public record ImageRecord(
        UUID id,
        UUID garmentId,
        UUID wardrobeId,
        UUID userId,
        String sourceS3Key,
        String mimeType,
        long expectedSize,
        String sourceChecksum,
        ProcessingStatus processingStatus,
        String assets,
        boolean deleteOriginalAfterIsolation,
        String originalFilename,
        ImageRole imageRole) {
    public String prefix() {
        return sourceS3Key.substring(0, sourceS3Key.lastIndexOf('/') + 1);
    }
}
