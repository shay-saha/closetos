package com.closetos.media.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UploadRequest(
        @NotBlank @Size(max = 255) String filename,
        @NotBlank String mimeType,
        @Positive long size,
        @NotBlank @Size(min = 44, max = 44) String checksumSha256,
        @NotNull ImageRole imageRole) {}
