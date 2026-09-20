package com.closetos.activity.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentMetadata;
import com.closetos.intelligence.api.SuggestionAccess;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class SuggestionReview {
    private static final Set<String> FIELDS =
            Arrays.stream(GarmentMetadata.class.getRecordComponents())
                    .map(component -> component.getName())
                    .collect(Collectors.toUnmodifiableSet());
    private final SuggestionAccess suggestions;
    private final GarmentAccess garments;
    private final WardrobeAccess wardrobes;
    private final JsonMapper json;

    public SuggestionReview(
            SuggestionAccess suggestions,
            GarmentAccess garments,
            WardrobeAccess wardrobes,
            JsonMapper json) {
        this.suggestions = suggestions;
        this.garments = garments;
        this.wardrobes = wardrobes;
        this.json = json;
    }

    @Transactional
    public GarmentDetails accept(
            UUID garment,
            UUID suggestion,
            long suggestionVersion,
            long garmentVersion,
            ObjectNode corrections) {
        garments.owned(garment);
        UUID wardrobe = wardrobes.currentWardrobeId();
        var current = suggestions.pending(suggestion, garment, wardrobe, suggestionVersion);
        ObjectNode patch = suggestions.canonicalValues(current);
        for (var entry : corrections.properties()) {
            if (!FIELDS.contains(entry.getKey()))
                throw DomainException.invalid("Unknown or read-only field: " + entry.getKey());
            patch.set(entry.getKey(), entry.getValue());
        }
        patch.put("version", garmentVersion);
        var confirmed = garments.patch(garment, patch);
        suggestions.accepted(suggestion, wardrobe, json.valueToTree(confirmed.metadata()));
        return confirmed;
    }

    @Transactional
    public void reject(UUID garment, UUID suggestion, long suggestionVersion) {
        garments.owned(garment);
        UUID wardrobe = wardrobes.currentWardrobeId();
        suggestions.pending(suggestion, garment, wardrobe, suggestionVersion);
        suggestions.rejected(suggestion, wardrobe);
    }
}
