package com.closetos.intelligence.application;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.intelligence.api.AnalysisDocument;
import com.closetos.intelligence.api.SuggestedValue;
import com.closetos.platform.api.DomainException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class SuggestionSchema {
    private static final Set<String> REQUIRED =
            Set.of("imageId", "pipelineVersion", "modelId", "promptVersion", "suggestions");
    private static final Set<String> DOCUMENT_FIELDS =
            Set.of(
                    "imageId",
                    "pipelineVersion",
                    "modelId",
                    "modelVersion",
                    "promptVersion",
                    "suggestions");
    private static final Map<String, FieldRule> FIELDS =
            Map.ofEntries(
                    Map.entry("category", new FieldRule("category", 30, 0)),
                    Map.entry("subcategory", new FieldRule("subcategory", 80, 0)),
                    Map.entry("primaryColour", new FieldRule("primaryColourName", 60, 0)),
                    Map.entry("secondaryColours", new FieldRule("secondaryColours", 60, 12)),
                    Map.entry("pattern", new FieldRule("pattern", 80, 0)),
                    Map.entry("materialEstimate", new FieldRule("material", 120, 0)),
                    Map.entry("length", new FieldRule("length", 60, 0)),
                    Map.entry("formality", new FieldRule("formality", 60, 0)),
                    Map.entry("seasonTags", new FieldRule("seasonTags", 60, 12)),
                    Map.entry("styleTags", new FieldRule("styleTags", 60, 24)),
                    Map.entry("occasionTags", new FieldRule("occasionTags", 60, 24)),
                    Map.entry("brand", new FieldRule("brand", 120, 0)),
                    Map.entry("notes", new FieldRule("notes", 4000, 0)));
    private final JsonMapper json =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    AnalysisDocument validate(UUID imageId, String pipeline, String document) {
        try {
            JsonNode root = json.readTree(document);
            if (!root.isObject()
                    || !root.propertyNames().containsAll(REQUIRED)
                    || !DOCUMENT_FIELDS.containsAll(root.propertyNames())
                    || !imageId.toString().equals(text(root, "imageId", 36))
                    || !pipeline.equals(text(root, "pipelineVersion", 40))) throw invalid();
            String model = text(root, "modelId", 300);
            String prompt = text(root, "promptVersion", 80);
            String modelVersion =
                    root.path("modelVersion").isMissingNode() || root.path("modelVersion").isNull()
                            ? null
                            : text(root, "modelVersion", 200);
            JsonNode fields = root.get("suggestions");
            if (!fields.isObject() || !fields.propertyNames().equals(FIELDS.keySet()))
                throw invalid();
            Map<String, SuggestedValue> values = new HashMap<>();
            for (var entry : fields.properties()) {
                JsonNode field = entry.getValue();
                if (!field.isObject()
                        || !field.propertyNames().equals(Set.of("value", "confidence")))
                    throw invalid();
                JsonNode score = field.get("confidence");
                if (!score.isNumber()
                        || !Double.isFinite(score.doubleValue())
                        || score.doubleValue() < 0
                        || score.doubleValue() > 1) throw invalid();
                JsonNode value = field.get("value");
                validateValue(entry.getKey(), value);
                values.put(entry.getKey(), new SuggestedValue(value, score.doubleValue()));
            }
            return new AnalysisDocument(
                    imageId, pipeline, model, modelVersion, prompt, Map.copyOf(values));
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    ObjectNode canonicalValues(Map<String, SuggestedValue> suggestions) {
        ObjectNode values = json.createObjectNode();
        suggestions.forEach(
                (field, suggestion) -> {
                    if (!suggestion.value().isNull())
                        values.set(FIELDS.get(field).canonical(), suggestion.value());
                });
        return values;
    }

    private void validateValue(String field, JsonNode value) {
        FieldRule rule = FIELDS.get(field);
        if (rule.maximumItems() > 0) {
            if (!value.isArray() || value.size() > rule.maximumItems()) throw invalid();
            for (JsonNode item : value) validateText(item, rule.maximumLength());
        } else if (!value.isNull()) {
            validateText(value, rule.maximumLength());
            if ("category".equals(field)) GarmentCategory.valueOf(value.asText());
        } else if ("category".equals(field)) throw invalid();
    }

    private String text(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        validateText(value, maximum);
        return value.asText();
    }

    private void validateText(JsonNode value, int maximum) {
        if (value == null
                || !value.isTextual()
                || value.asText().isBlank()
                || value.asText().length() > maximum) throw invalid();
    }

    private DomainException invalid() {
        return DomainException.invalid(
                "The analysis response did not match the garment metadata schema.");
    }

    private record FieldRule(String canonical, int maximumLength, int maximumItems) {}
}
