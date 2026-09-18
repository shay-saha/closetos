package com.closetos.garment.infrastructure;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentFilter;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.domain.Garment;
import com.closetos.platform.api.DomainException;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogueQuery {
    private final EntityManager entityManager;
    private final CatalogueCursor cursors;

    CatalogueQuery(EntityManager entityManager, CatalogueCursor cursors) {
        this.entityManager = entityManager;
        this.cursors = cursors;
    }

    public GarmentPage find(UUID wardrobe, GarmentFilter filter) {
        List<String> conditions = new ArrayList<>(List.of("wardrobe_id = :wardrobe"));
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of("wardrobe", wardrobe));
        if (filter.status() == null) {
            conditions.add("status <> 'ARCHIVED'");
        }
        match(
                conditions,
                parameters,
                "category = :category",
                "category",
                filter.category() == null ? null : filter.category().name());
        match(
                conditions,
                parameters,
                "status = :status",
                "status",
                filter.status() == null ? null : filter.status().name());
        match(
                conditions,
                parameters,
                "lower(subcategory) = lower(:subcategory)",
                "subcategory",
                filter.subcategory());
        match(conditions, parameters, "lower(brand) = lower(:brand)", "brand", filter.brand());
        match(
                conditions,
                parameters,
                "lower(primary_colour_name) = lower(:colour)",
                "colour",
                filter.colour());
        match(conditions, parameters, "size_label = :size", "size", filter.size());
        match(
                conditions,
                parameters,
                "lower(formality) = lower(:formality)",
                "formality",
                filter.formality());
        match(
                conditions,
                parameters,
                "jsonb_exists(season_tags, :season)",
                "season",
                filter.season());
        match(
                conditions,
                parameters,
                "jsonb_exists(occasion_tags, :occasion)",
                "occasion",
                filter.occasion());
        match(conditions, parameters, "jsonb_exists(style_tags, :tag)", "tag", filter.tag());
        match(
                conditions,
                parameters,
                "wear_count_cached >= :minWear",
                "minWear",
                filter.minWearCount());
        match(
                conditions,
                parameters,
                "wear_count_cached <= :maxWear",
                "maxWear",
                filter.maxWearCount());
        match(
                conditions,
                parameters,
                "(last_worn_at IS NULL OR last_worn_at < :notWornSince)",
                "notWornSince",
                filter.notWornSince());
        match(
                conditions,
                parameters,
                "purchase_date >= :purchasedAfter",
                "purchasedAfter",
                filter.purchasedAfter());
        match(
                conditions,
                parameters,
                "purchase_date <= :purchasedBefore",
                "purchasedBefore",
                filter.purchasedBefore());
        if (filter.q() != null && !filter.q().isBlank()) {
            match(
                    conditions,
                    parameters,
                    "search_document @@ websearch_to_tsquery('english', :query)",
                    "query",
                    filter.q());
        }
        CatalogueSort sort = CatalogueSort.of(filter.sort());
        if (filter.cursor() != null) {
            CatalogueCursor.Position position = cursors.decode(filter.cursor(), filter);
            Object value;
            try {
                value = sort.parse(position.value());
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
                throw DomainException.invalid("Invalid pagination cursor.");
            }
            conditions.add(
                    "("
                            + sort.expression()
                            + ", id) "
                            + sort.comparison()
                            + " (:cursorValue, :cursorId)");
            parameters.put("cursorValue", value);
            parameters.put("cursorId", position.id());
        }
        String sql =
                "SELECT * FROM garment WHERE "
                        + String.join(" AND ", conditions)
                        + " ORDER BY "
                        + sort.expression()
                        + " "
                        + sort.order()
                        + ", id "
                        + sort.order();
        var query =
                entityManager
                        .createNativeQuery(sql, Garment.class)
                        .setMaxResults(filter.limit() + 1);
        parameters.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Garment> found = query.getResultList();
        List<GarmentDetails> items =
                found.stream().limit(filter.limit()).map(Garment::details).toList();
        String next =
                found.size() <= filter.limit()
                        ? null
                        : cursors.encode(items.getLast().id(), sort.value(items.getLast()), filter);
        return new GarmentPage(items, next);
    }

    private static void match(
            List<String> conditions,
            Map<String, Object> parameters,
            String expression,
            String parameter,
            Object value) {
        if (value != null) {
            conditions.add(expression);
            parameters.put(parameter, value);
        }
    }
}
