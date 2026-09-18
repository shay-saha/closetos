package com.closetos.garment.application;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentFilter;
import com.closetos.garment.api.GarmentMetadata;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.garment.domain.Garment;
import com.closetos.garment.infrastructure.CatalogueQuery;
import com.closetos.garment.infrastructure.GarmentRepository;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class GarmentService {
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

    public GarmentService(
            GarmentRepository garments,
            WardrobeAccess wardrobe,
            Clock clock,
            JsonMapper json,
            Validator validator,
            CatalogueQuery catalogue) {
        this.garments = garments;
        this.wardrobe = wardrobe;
        this.clock = clock;
        this.json = json;
        this.validator = validator;
        this.catalogue = catalogue;
    }

    @Transactional
    public GarmentDetails create(GarmentMetadata metadata) {
        validate(metadata);
        return garments.saveAndFlush(
                        new Garment(wardrobe.currentWardrobeId(), metadata, clock.instant()))
                .details();
    }

    @Transactional
    public GarmentDetails get(UUID id) {
        return owned(id).details();
    }

    @Transactional
    public GarmentPage list(GarmentFilter filter) {
        return catalogue.find(wardrobe.currentWardrobeId(), filter);
    }

    @Transactional
    public GarmentDetails patch(UUID id, ObjectNode patch) {
        Garment garment = owned(id);
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
        return garments.saveAndFlush(garment).details();
    }

    @Transactional
    public GarmentDetails status(UUID id, GarmentStatus status, long version) {
        Garment garment = owned(id);
        garment.changeStatus(status, version, clock.instant());
        return garments.saveAndFlush(garment).details();
    }

    @Transactional
    public void delete(UUID id, long version) {
        Garment garment = owned(id);
        garment.update(garment.metadata(), version, clock.instant());
        garments.delete(garment);
        garments.flush();
    }

    private Garment owned(UUID id) {
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
