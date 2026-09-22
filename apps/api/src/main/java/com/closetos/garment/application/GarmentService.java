package com.closetos.garment.application;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentFilter;
import com.closetos.garment.api.GarmentMetadata;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentSelection;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.garment.domain.Garment;
import com.closetos.garment.infrastructure.CatalogueQuery;
import com.closetos.garment.infrastructure.GarmentRepository;
import com.closetos.garment.infrastructure.SmartQueryCompiler;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class GarmentService implements com.closetos.garment.api.GarmentAccess {
    private static final Set<String> METADATA_FIELDS =
            Arrays.stream(GarmentMetadata.class.getRecordComponents())
                    .map(component -> component.getName())
                    .collect(Collectors.toUnmodifiableSet());
    private final GarmentRepository garments;
    private final WardrobeAccess wardrobe;
    private final Clock clock;
    private final JsonMapper json;
    private final Validator validator;
    private final CatalogueQuery catalogue;
    private final SmartQueryCompiler smartQueries;
    private final com.closetos.media.api.MediaAccess media;
    private final com.closetos.platform.api.OutboxAccess outbox;

    public GarmentService(
            GarmentRepository garments,
            WardrobeAccess wardrobe,
            Clock clock,
            JsonMapper json,
            Validator validator,
            CatalogueQuery catalogue,
            SmartQueryCompiler smartQueries,
            com.closetos.media.api.MediaAccess media,
            com.closetos.platform.api.OutboxAccess outbox) {
        this.garments = garments;
        this.wardrobe = wardrobe;
        this.clock = clock;
        this.json = json;
        this.validator = validator;
        this.catalogue = catalogue;
        this.smartQueries = smartQueries;
        this.media = media;
        this.outbox = outbox;
    }

    @Transactional
    public GarmentDetails create(GarmentMetadata metadata) {
        validate(metadata);
        var created =
                garments.saveAndFlush(
                                new Garment(
                                        wardrobe.currentWardrobeId(), metadata, clock.instant()))
                        .details();
        enqueueEmbedding(created);
        return created;
    }

    @Override
    @Transactional
    public List<UUID> matchingIds(GarmentFilter filter, ObjectNode constraints) {
        return catalogue.matchingIds(wardrobe.currentWardrobeId(), filter, constraints);
    }

    @Override
    @Transactional
    public List<GarmentDetails> ownedDetails(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return garments.findByWardrobeIdAndIdIn(wardrobe.currentWardrobeId(), ids).stream()
                .map(Garment::details)
                .toList();
    }

    @Override
    @Transactional
    public Optional<GarmentDetails> embeddingSnapshot(UUID id, UUID wardrobeId) {
        return garments.embeddingSnapshot(id, wardrobeId).map(Garment::details);
    }

    private void enqueueEmbedding(GarmentDetails garment) {
        outbox.enqueue(
                "garment",
                garment.id(),
                "GENERATE_EMBEDDING",
                "embedding:" + garment.id() + ":" + garment.version(),
                Map.of("wardrobeId", wardrobe.currentWardrobeId()));
    }

    @Override
    @Transactional
    public void lockOwned(List<UUID> ids) {
        if (ids.isEmpty()) return;
        var locked = garments.lockOwned(wardrobe.currentWardrobeId(), ids);
        if (locked.size() != ids.size()) throw DomainException.notFound("Garment");
    }

    @Override
    @Transactional
    public List<UUID> ownedIds() {
        return garments.ownedIds(wardrobe.currentWardrobeId());
    }

    @Override
    @Transactional
    public void updateWearStatistics(UUID id, int count, LocalDate lastWorn) {
        var garment = ownedEntity(id);
        garment.updateWearStatistics(count, lastWorn, clock.instant());
        garments.saveAndFlush(garment);
    }

    @Transactional
    public GarmentDetails get(UUID id) {
        return ownedEntity(id).details();
    }

    @Transactional
    public GarmentPage list(GarmentFilter filter) {
        return catalogue.find(wardrobe.currentWardrobeId(), filter);
    }

    @Override
    public void validateSmartQuery(ObjectNode query) {
        smartQueries.compile(query);
    }

    @Override
    @Transactional
    public GarmentPage select(GarmentFilter filter, GarmentSelection selection) {
        return catalogue.find(wardrobe.currentWardrobeId(), filter, selection);
    }

    @Transactional
    public GarmentDetails patch(UUID id, ObjectNode patch) {
        Garment garment = ownedEntity(id);
        JsonNode version = patch.get("version");
        if (version == null
                || !version.isIntegralNumber()
                || !version.canConvertToLong()
                || version.asLong() < 0) {
            throw DomainException.invalid("A current version is required.");
        }
        ObjectNode merged = json.valueToTree(garment.metadata());
        patch.properties()
                .forEach(
                        entry -> {
                            if (!"version".equals(entry.getKey())) {
                                if (!METADATA_FIELDS.contains(entry.getKey())) {
                                    throw DomainException.invalid(
                                            "Unknown or read-only field: " + entry.getKey());
                                }
                                merged.set(entry.getKey(), entry.getValue());
                            }
                        });
        GarmentMetadata metadata;
        try {
            metadata = json.treeToValue(merged, GarmentMetadata.class);
        } catch (tools.jackson.core.JacksonException exception) {
            throw DomainException.invalid("Check the garment fields and try again.");
        }
        validate(metadata);
        garment.update(metadata, version.asLong(), clock.instant());
        garment.confirmMetadata();
        media.confirmImages(garment.id(), wardrobe.currentWardrobeId());
        var saved = garments.saveAndFlush(garment).details();
        enqueueEmbedding(saved);
        return saved;
    }

    @Transactional
    public GarmentDetails status(UUID id, GarmentStatus status, long version) {
        Garment garment = ownedEntity(id);
        garment.changeStatus(status, version, clock.instant());
        return garments.saveAndFlush(garment).details();
    }

    @Transactional
    public void delete(UUID id, long version) {
        Garment garment = ownedEntity(id);
        garment.update(garment.metadata(), version, clock.instant());
        media.deleteGarmentImages(id, wardrobe.currentWardrobeId());
        garments.delete(garment);
        garments.flush();
    }

    @Override
    @Transactional
    public GarmentDetails createDraft(UUID wardrobeId) {
        if (!wardrobe.currentWardrobeId().equals(wardrobeId))
            throw DomainException.notFound("Wardrobe");
        return garments.saveAndFlush(Garment.draft(wardrobeId, clock.instant())).details();
    }

    @Override
    @Transactional
    public GarmentDetails owned(UUID garment) {
        return ownedEntity(garment).details();
    }

    @Override
    @Transactional
    public void processingState(
            UUID garment, UUID wardrobeId, com.closetos.media.api.ProcessingStatus state) {
        garments.findByIdAndWardrobeId(garment, wardrobeId)
                .ifPresent(item -> item.processingState(state, clock.instant()));
    }

    private Garment ownedEntity(UUID id) {
        return garments.findByIdAndWardrobeId(id, wardrobe.currentWardrobeId())
                .orElseThrow(() -> DomainException.notFound("Garment"));
    }

    private void validate(GarmentMetadata metadata) {
        if (!validator.validate(metadata).isEmpty()) {
            throw DomainException.invalid("Check the garment fields and try again.");
        }
        if (metadata.purchasePrice() != null && metadata.purchaseCurrency() == null) {
            throw DomainException.invalid(
                    "A currency is required when a purchase price is supplied.");
        }
    }
}
