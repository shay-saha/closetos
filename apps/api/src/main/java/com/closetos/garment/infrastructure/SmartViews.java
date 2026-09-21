package com.closetos.garment.infrastructure;

import com.closetos.garment.api.GarmentView;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class SmartViews {
    private final JsonMapper json;

    SmartViews(JsonMapper json) {
        this.json = json;
    }

    ObjectNode combine(ObjectNode query, GarmentView view) {
        if (view == null) return query;
        ObjectNode preset =
                switch (view) {
                    case RECENTLY_ADDED, LEAST_WORN -> rule("createdDate", "IS_NOT_NULL", null);
                    case RECENTLY_WORN -> rule("lastWornDate", "IS_NOT_NULL", null);
                    case MOST_WORN -> rule("wearCount", "GT", 0);
                    case NEVER_WORN -> rule("wearCount", "EQ", 0);
                    case FORGOTTEN -> all(rule("ownedDays", "GT", 180), rule("wearCount", "LT", 3));
                    case BEST_COST_PER_WEAR, HIGHEST_COST_PER_WEAR ->
                            rule("purchasePrice", "IS_NOT_NULL", null);
                    case CURRENT_SEASON -> rule("season", "CONTAINS_CURRENT", null);
                    case GOING_OUT -> rule("occasion", "CONTAINS", "Going Out");
                    case WORK -> rule("occasion", "CONTAINS", "Work");
                    case FORMAL -> rule("formality", "EQ", "Formal");
                    case PACKED, LAUNDRY, ARCHIVED -> rule("status", "EQ", view.name());
                };
        return query == null ? preset : all(query, preset);
    }

    private ObjectNode rule(String field, String operator, Object value) {
        var rule = json.createObjectNode().put("field", field).put("operator", operator);
        if (value != null) rule.set("value", json.valueToTree(value));
        return rule;
    }

    private ObjectNode all(ObjectNode first, ObjectNode second) {
        return json.createObjectNode().set("all", json.createArrayNode().add(first).add(second));
    }
}
