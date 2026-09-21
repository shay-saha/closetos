package com.closetos.garment.infrastructure;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.platform.api.DomainException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class SmartQueryCompiler {
    private static final Set<String> COMPARISONS = Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE");
    private static final Map<String, Field> FIELDS =
            Map.ofEntries(
                    Map.entry("name", new Field("name", Kind.TEXT, 160)),
                    Map.entry("category", new Field("category", Kind.CATEGORY, 40)),
                    Map.entry("status", new Field("status", Kind.STATUS, 40)),
                    Map.entry(
                            "processingStatus",
                            new Field("processing_status", Kind.PROCESSING, 40)),
                    Map.entry("subcategory", new Field("subcategory", Kind.TEXT, 80)),
                    Map.entry("colour", new Field("primary_colour_name", Kind.TEXT, 60)),
                    Map.entry("brand", new Field("brand", Kind.TEXT, 120)),
                    Map.entry("size", new Field("size_label", Kind.TEXT, 40)),
                    Map.entry("formality", new Field("formality", Kind.TEXT, 60)),
                    Map.entry("season", new Field("season_tags", Kind.TAGS, 60)),
                    Map.entry("occasion", new Field("occasion_tags", Kind.TAGS, 60)),
                    Map.entry("style", new Field("style_tags", Kind.TAGS, 60)),
                    Map.entry("wearCount", new Field("wear_count_cached", Kind.INTEGER, 0)),
                    Map.entry(
                            "ownedDays",
                            new Field("(:smartToday - created_at::date)", Kind.INTEGER, 0)),
                    Map.entry(
                            "daysSinceLastWorn",
                            new Field("(:smartToday - last_worn_at)", Kind.INTEGER, 0)),
                    Map.entry("purchasePrice", new Field("purchase_price", Kind.DECIMAL, 0)),
                    Map.entry(
                            "costPerWear",
                            new Field(
                                    "round(purchase_price / greatest(wear_count_cached, 1), 2)",
                                    Kind.DECIMAL,
                                    0)),
                    Map.entry("purchaseDate", new Field("purchase_date", Kind.DATE, 0)),
                    Map.entry("createdDate", new Field("created_at::date", Kind.DATE, 0)),
                    Map.entry("lastWornDate", new Field("last_worn_at", Kind.DATE, 0)));
    private final Clock clock;

    public SmartQueryCompiler(Clock clock) {
        this.clock = clock;
    }

    public Compiled compile(ObjectNode query) {
        return compile(query, 100, 8, 32768);
    }

    public Compiled compileForCatalogue(ObjectNode query) {
        return compile(query, 128, 10, 65536);
    }

    private Compiled compile(ObjectNode query, int maxNodes, int maxDepth, int maxLength) {
        if (query == null || query.toString().length() > maxLength) throw invalid();
        var state = new State(LocalDate.now(clock), maxNodes, maxDepth);
        String condition = node(query, state, 0);
        return new Compiled(condition, Map.copyOf(state.parameters), state.hasStatus, state.today);
    }

    private String node(JsonNode node, State state, int depth) {
        if (!node.isObject() || depth > state.maxDepth || ++state.nodes > state.maxNodes)
            throw invalid();
        for (String group : List.of("all", "any")) {
            if (!node.has(group)) continue;
            var children = node.get(group);
            if (node.size() != 1
                    || !children.isArray()
                    || children.isEmpty()
                    || children.size() > 50) throw invalid();
            List<String> conditions = new ArrayList<>();
            for (var child : children) conditions.add(node(child, state, depth + 1));
            return "(" + String.join(group.equals("all") ? " AND " : " OR ", conditions) + ")";
        }
        if (node.has("not")) {
            if (node.size() != 1) throw invalid();
            return "(NOT " + node(node.get("not"), state, depth + 1) + ")";
        }
        if (!Set.of("field", "operator", "value").containsAll(node.propertyNames())
                || !node.path("field").isString()
                || !node.path("operator").isString()) throw invalid();
        String name = node.get("field").asText();
        var field = FIELDS.get(name);
        if (field == null) throw invalid();
        if (name.equals("ownedDays") || name.equals("daysSinceLastWorn"))
            state.parameters.put("smartToday", state.today);
        if (name.equals("status")) state.hasStatus = true;
        String operator = node.get("operator").asText();
        String column = field.expression;
        if (operator.equals("IS_NULL") || operator.equals("IS_NOT_NULL")) {
            if (node.has("value") || field.kind == Kind.TAGS) throw invalid();
            return "(" + column + (operator.equals("IS_NULL") ? " IS NULL)" : " IS NOT NULL)");
        }
        if (operator.equals("CONTAINS_CURRENT")) {
            if (!name.equals("season") || node.has("value")) throw invalid();
            String season =
                    switch (state.today.getMonthValue()) {
                        case 3, 4, 5 -> "spring";
                        case 6, 7, 8 -> "summer";
                        case 9, 10, 11 -> "autumn";
                        default -> "winter";
                    };
            String parameter = bind(state, season);
            return "(EXISTS (SELECT 1 FROM jsonb_array_elements_text("
                    + column
                    + ") AS smart_tag(value) WHERE lower(smart_tag.value) IN ("
                    + parameter
                    + ", 'all season', 'all seasons', 'year round')))";
        }
        JsonNode value = node.get("value");
        if (value == null || value.isNull()) throw invalid();
        if (operator.equals("IN") || operator.equals("NOT_IN")) {
            if (field.kind == Kind.TAGS || !value.isArray() || value.isEmpty() || value.size() > 50)
                throw invalid();
            List<String> parameters = new ArrayList<>();
            for (var item : value) parameters.add(bind(state, value(field, item)));
            return "("
                    + normalized(field)
                    + (operator.equals("IN") ? " IN (" : " NOT IN (")
                    + String.join(", ", parameters)
                    + "))";
        }
        Object typed = value(field, value);
        String parameter = bind(state, typed);
        if (field.kind == Kind.TAGS) {
            if (!operator.equals("CONTAINS") && !operator.equals("NOT_CONTAINS")) throw invalid();
            return "("
                    + (operator.equals("NOT_CONTAINS") ? "NOT " : "")
                    + "EXISTS (SELECT 1 FROM jsonb_array_elements_text("
                    + column
                    + ") AS smart_tag(value) WHERE lower(smart_tag.value) = "
                    + parameter
                    + "))";
        }
        if (operator.equals("CONTAINS")) {
            if (field.kind != Kind.TEXT) throw invalid();
            return "(strpos(lower(" + column + "), " + parameter + ") > 0)";
        }
        if (!COMPARISONS.contains(operator)
                || (!operator.equals("EQ")
                        && !operator.equals("NE")
                        && (field.kind == Kind.TEXT
                                || field.kind == Kind.CATEGORY
                                || field.kind == Kind.STATUS
                                || field.kind == Kind.PROCESSING))) throw invalid();
        String comparison =
                switch (operator) {
                    case "EQ" -> "=";
                    case "NE" -> "<>";
                    case "GT" -> ">";
                    case "GTE" -> ">=";
                    case "LT" -> "<";
                    case "LTE" -> "<=";
                    default -> throw invalid();
                };
        return "(" + normalized(field) + " " + comparison + " " + parameter + ")";
    }

    private Object value(Field field, JsonNode value) {
        try {
            return switch (field.kind) {
                case INTEGER -> {
                    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0)
                        throw invalid();
                    yield value.asInt();
                }
                case DECIMAL -> {
                    if (!value.isNumber()) throw invalid();
                    BigDecimal number = value.decimalValue();
                    if (number.signum() < 0
                            || number.compareTo(new BigDecimal("9999999999.99")) > 0
                            || number.scale() > 2) throw invalid();
                    yield number;
                }
                case DATE -> {
                    if (!value.isString() || value.asText().length() != 10) throw invalid();
                    yield LocalDate.parse(value.asText());
                }
                case TEXT, TAGS, CATEGORY, STATUS, PROCESSING -> {
                    if (!value.isString()
                            || value.asText().isBlank()
                            || value.asText().length() > field.maxLength) throw invalid();
                    String text = value.asText().strip();
                    yield switch (field.kind) {
                        case CATEGORY -> GarmentCategory.valueOf(text).name();
                        case STATUS -> GarmentStatus.valueOf(text).name();
                        case PROCESSING -> ProcessingStatus.valueOf(text).name();
                        default -> text.toLowerCase(java.util.Locale.ROOT);
                    };
                }
            };
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalid();
        }
    }

    private String normalized(Field field) {
        return field.kind == Kind.TEXT ? "lower(" + field.expression + ")" : field.expression;
    }

    private String bind(State state, Object value) {
        String name = "smartValue" + state.values++;
        state.parameters.put(name, value);
        return ":" + name;
    }

    private static DomainException invalid() {
        return DomainException.invalid(
                "Check the smart collection fields, operators and values. Queries allow up to 100 nodes and eight nested groups.");
    }

    public record Compiled(
            String condition,
            Map<String, Object> parameters,
            boolean hasStatus,
            LocalDate evaluatedOn) {}

    private record Field(String expression, Kind kind, int maxLength) {}

    private enum Kind {
        TEXT,
        TAGS,
        INTEGER,
        DECIMAL,
        DATE,
        CATEGORY,
        STATUS,
        PROCESSING
    }

    private static final class State {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        final LocalDate today;
        final int maxNodes;
        final int maxDepth;
        int nodes;
        int values;
        boolean hasStatus;

        State(LocalDate today, int maxNodes, int maxDepth) {
            this.today = today;
            this.maxNodes = maxNodes;
            this.maxDepth = maxDepth;
        }
    }
}
