package com.closetos.outfit.domain;

import com.closetos.outfit.api.OutfitDetails;
import com.closetos.outfit.api.OutfitInput;
import com.closetos.outfit.api.OutfitItem;
import com.closetos.outfit.api.OutfitMetadata;
import com.closetos.platform.api.DomainException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
public class Outfit {
    @Id private UUID id;
    private UUID wardrobeId;
    @Embedded private OutfitMetadata metadata;

    @ElementCollection
    @CollectionTable(name = "outfit_item", joinColumns = @JoinColumn(name = "outfit_id"))
    @OrderBy("zIndex ASC, garmentId ASC")
    private List<OutfitItem> items = new ArrayList<>();

    @Version private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Outfit() {}

    public Outfit(UUID wardrobeId, OutfitInput input, Instant now) {
        id = UUID.randomUUID();
        this.wardrobeId = wardrobeId;
        metadata = input.metadata();
        items.addAll(input.items());
        createdAt = now;
        updatedAt = now;
    }

    public void update(OutfitInput input, long expectedVersion, Instant now) {
        verifyVersion(expectedVersion);
        metadata = input.metadata();
        items.clear();
        items.addAll(input.items());
        updatedAt = now;
    }

    public void verifyVersion(long expectedVersion) {
        if (version != expectedVersion) throw DomainException.conflict();
    }

    public OutfitDetails details() {
        return new OutfitDetails(id, metadata, List.copyOf(items), version, createdAt, updatedAt);
    }
}
