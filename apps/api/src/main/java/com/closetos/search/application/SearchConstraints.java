package com.closetos.search.application;

import com.closetos.search.api.SearchPage.Constraint;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class SearchConstraints {
    private static final Map<String, String> CATEGORIES =
            Map.ofEntries(
                    Map.entry("tops?", "TOP"),
                    Map.entry("shirts?", "TOP"),
                    Map.entry("t-shirts?", "TOP"),
                    Map.entry("jumpers?", "TOP"),
                    Map.entry("sweaters?", "TOP"),
                    Map.entry("blouses?", "TOP"),
                    Map.entry("dress(?:es)?", "DRESS"),
                    Map.entry("skirts?", "BOTTOM"),
                    Map.entry("trousers?", "BOTTOM"),
                    Map.entry("pants", "BOTTOM"),
                    Map.entry("jeans", "BOTTOM"),
                    Map.entry("shorts", "BOTTOM"),
                    Map.entry("coats?", "OUTERWEAR"),
                    Map.entry("jackets?", "OUTERWEAR"),
                    Map.entry("shoes?", "SHOES"),
                    Map.entry("boots?", "SHOES"),
                    Map.entry("heels?", "SHOES"),
                    Map.entry("trainers?", "SHOES"),
                    Map.entry("sneakers?", "SHOES"),
                    Map.entry("bags?", "BAG"),
                    Map.entry("handbags?", "BAG"),
                    Map.entry("jewellery", "JEWELLERY"),
                    Map.entry("jewelry", "JEWELLERY"),
                    Map.entry("accessories", "ACCESSORY"));
    private final JsonMapper json;
    private final Clock clock;

    public SearchConstraints(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    public Parsed parse(String query) {
        if (query == null) return new Parsed(null, List.of());
        String text = query.toLowerCase(Locale.ROOT);
        var constraints = new ArrayList<Constraint>();
        vocabulary(constraints, "category", text, CATEGORIES);
        var colours = new LinkedHashMap<String, String>();
        for (String colour :
                List.of(
                        "black", "white", "cream", "beige", "brown", "grey", "gray", "blue", "navy",
                        "green", "olive", "red", "pink", "purple", "yellow", "orange"))
            colours.put(colour, colour);
        vocabulary(constraints, "colour", text, colours);
        vocabulary(
                constraints,
                "formality",
                text,
                Map.of(
                        "formal",
                        "formal",
                        "(?<!smart )casual",
                        "casual",
                        "smart casual",
                        "smart casual"));
        for (String season : List.of("spring", "summer", "autumn", "winter")) {
            var mention = Pattern.compile("\\b" + season + "\\b").matcher(text);
            if (mention.find()) {
                boolean excluded = negated(text, mention.start());
                constraints.add(
                        new Constraint(
                                "season",
                                excluded ? "NOT_CONTAINS" : "CONTAINS",
                                season,
                                (excluded ? "Exclude season: " : "Season: ") + season));
            }
        }
        var size =
                Pattern.compile("\\bsize (xxs|xs|s|m|l|xl|xxl|[0-9]{1,3}(?:\\.[05])?)\\b")
                        .matcher(text);
        if (size.find())
            constraints.add(
                    new Constraint(
                            "size",
                            "EQ",
                            size.group(1).toUpperCase(Locale.ROOT),
                            "Size: " + size.group(1).toUpperCase(Locale.ROOT)));
        var wearCount =
                Pattern.compile("\\bworn (fewer|less|more) than ([0-9]{1,5}) times\\b")
                        .matcher(text);
        if (wearCount.find())
            constraints.add(
                    new Constraint(
                            "wearCount",
                            wearCount.group(1).equals("more") ? "GT" : "LT",
                            Integer.parseInt(wearCount.group(2)),
                            "Worn "
                                    + wearCount.group(1)
                                    + " than "
                                    + wearCount.group(2)
                                    + " times"));
        var purchased =
                Pattern.compile(
                                "\\b(?:bought|purchased) (after|before) ([0-9]{4}-[0-9]{2}-[0-9]{2})\\b")
                        .matcher(text);
        if (purchased.find())
            constraints.add(
                    new Constraint(
                            "purchaseDate",
                            purchased.group(1).equals("after") ? "GTE" : "LTE",
                            purchased.group(2),
                            "Purchased " + purchased.group(1) + " " + purchased.group(2)));
        if (text.contains("never worn"))
            constraints.add(new Constraint("wearCount", "EQ", 0, "Never worn"));
        if (text.contains("not worn recently")) {
            var cutoff = LocalDate.now(clock).minusDays(30);
            constraints.add(
                    new Constraint(
                            "lastWornDate",
                            "BEFORE_OR_NEVER",
                            cutoff.toString(),
                            "Not worn in the past 30 days"));
        }
        var days = Pattern.compile("\\bnot worn (?:in|for) (\\d{1,4}) days\\b").matcher(text);
        if (days.find()) {
            var cutoff = LocalDate.now(clock).minusDays(Integer.parseInt(days.group(1)));
            constraints.add(
                    new Constraint(
                            "lastWornDate",
                            "BEFORE_OR_NEVER",
                            cutoff.toString(),
                            "Not worn since " + cutoff));
        }
        var statuses = new LinkedHashMap<String, String>();
        statuses.put("available", "AVAILABLE");
        statuses.put("laundry", "LAUNDRY");
        statuses.put("packed", "PACKED");
        statuses.put("archived", "ARCHIVED");
        vocabulary(constraints, "status", text, statuses);
        if (constraints.isEmpty()) return new Parsed(null, List.of());
        var rules = json.createArrayNode();
        for (var constraint : constraints) {
            if (constraint.operator().equals("BEFORE_OR_NEVER")) {
                var any =
                        json.createArrayNode()
                                .add(rule("lastWornDate", "IS_NULL", null))
                                .add(rule("lastWornDate", "LT", constraint.value()));
                rules.add(json.createObjectNode().set("any", any));
            } else rules.add(rule(constraint.field(), constraint.operator(), constraint.value()));
        }
        return new Parsed(json.createObjectNode().set("all", rules), List.copyOf(constraints));
    }

    private void vocabulary(
            List<Constraint> constraints, String field, String text, Map<String, String> words) {
        var positive = new java.util.TreeSet<String>();
        var negative = new java.util.TreeSet<String>();
        words.forEach(
                (word, value) -> {
                    var matcher =
                            Pattern.compile("(?<![\\p{L}\\p{N}-])(?:" + word + ")\\b")
                                    .matcher(text);
                    while (matcher.find())
                        (negated(text, matcher.start()) ? negative : positive).add(value);
                });
        if (!positive.isEmpty())
            constraints.add(
                    new Constraint(
                            field,
                            "IN",
                            List.copyOf(positive),
                            field.substring(0, 1).toUpperCase(Locale.ROOT)
                                    + field.substring(1)
                                    + ": "
                                    + String.join(" or ", positive).toLowerCase(Locale.ROOT)));
        if (!negative.isEmpty())
            constraints.add(
                    new Constraint(
                            field,
                            "NOT_IN",
                            List.copyOf(negative),
                            "Exclude "
                                    + field
                                    + ": "
                                    + String.join(" or ", negative).toLowerCase(Locale.ROOT)));
    }

    private boolean negated(String text, int start) {
        return Pattern.compile(
                        "\\b(?:not|except|excluding|without|other than)\\s+(?:a\\s+|an\\s+|the\\s+)?$")
                .matcher(text.substring(0, start))
                .find();
    }

    private ObjectNode rule(String field, String operator, Object value) {
        var rule = json.createObjectNode().put("field", field).put("operator", operator);
        if (value != null) rule.set("value", json.valueToTree(value));
        return rule;
    }

    public record Parsed(ObjectNode query, List<Constraint> constraints) {}
}
