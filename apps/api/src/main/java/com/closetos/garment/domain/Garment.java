package com.closetos.garment.domain;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentMetadata;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.platform.api.DomainException;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
public class Garment {
    @Id private UUID id;
    private UUID wardrobeId;
    @Embedded private GarmentMetadata metadata;

    @Enumerated(EnumType.STRING)
    private GarmentStatus status;

    @Enumerated(EnumType.STRING)
    private ProcessingStatus processingStatus;

    private int wearCountCached;
    private LocalDate lastWornAt;
    @Version private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Garment() {}

    public Garment(UUID wardrobeId, GarmentMetadata metadata, Instant now) {
        this.id = UUID.randomUUID();
        this.wardrobeId = wardrobeId;
        this.metadata = metadata;
        this.status = GarmentStatus.AVAILABLE;
        this.processingStatus = ProcessingStatus.READY;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Garment draft(UUID wardrobe, Instant now) {
        var metadata =
                new GarmentMetadata(
                        "New piece",
                        com.closetos.garment.api.GarmentCategory.OTHER,
                        null,
                        null,
                        null,
                        null,
                        null,
                        java.util.List.of(),
                        null,
                        null,
                        null,
                        null,
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        null,
                        null,
                        null,
                        null);
        var garment = new Garment(wardrobe, metadata, now);
        garment.processingStatus = ProcessingStatus.AWAITING_UPLOAD;
        return garment;
    }

    public void processingState(ProcessingStatus next, Instant now) {
        if (processingStatus == ProcessingStatus.READY) return;
        processingStatus = next;
        updatedAt = now;
    }

    public void confirmMetadata() {
        processingStatus = ProcessingStatus.READY;
    }

    public void updateWearStatistics(int count, LocalDate lastWorn, Instant now) {
        if (count < 0 || (count == 0) != (lastWorn == null))
            throw new IllegalArgumentException("Wear statistics are inconsistent.");
        wearCountCached = count;
        lastWornAt = lastWorn;
        updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public GarmentMetadata metadata() {
        return metadata;
    }

    public void update(GarmentMetadata metadata, long expectedVersion, Instant now) {
        verifyVersion(expectedVersion);
        this.metadata = metadata;
        this.updatedAt = now;
    }

    public void changeStatus(GarmentStatus status, long expectedVersion, Instant now) {
        verifyVersion(expectedVersion);
        this.status = status;
        this.updatedAt = now;
    }

    private void verifyVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw DomainException.conflict();
        }
    }

    public GarmentDetails details() {
        BigDecimal cost =
                metadata.purchasePrice() == null
                        ? null
                        : metadata.purchasePrice()
                                .divide(
                                        BigDecimal.valueOf(Math.max(wearCountCached, 1)),
                                        2,
                                        RoundingMode.HALF_UP);
        return new GarmentDetails(
                id,
                metadata,
                status,
                processingStatus,
                wearCountCached,
                lastWornAt,
                cost,
                version,
                createdAt,
                updatedAt,
                null);
    }
}
