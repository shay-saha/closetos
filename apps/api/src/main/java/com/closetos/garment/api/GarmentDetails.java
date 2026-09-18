package com.closetos.garment.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record GarmentDetails(
        UUID id,
        @JsonUnwrapped GarmentMetadata metadata,
        GarmentStatus status,
        ProcessingStatus processingStatus,
        int wearCount,
        LocalDate lastWornAt,
        BigDecimal costPerWear,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
