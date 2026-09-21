package com.closetos.outfit.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.outfit.api.OutfitAccess;
import com.closetos.outfit.api.OutfitDetails;
import com.closetos.outfit.api.OutfitInput;
import com.closetos.outfit.api.OutfitItem;
import com.closetos.outfit.api.OutfitMetadata;
import com.closetos.outfit.api.OutfitPage;
import com.closetos.outfit.domain.Outfit;
import com.closetos.outfit.infrastructure.OutfitRepository;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OutfitService implements OutfitAccess {
    private static final Set<String> FIELDS =
            Set.of("name", "occasion", "season", "rating", "tags", "notes", "archived", "items");
    private final OutfitRepository outfits;
    private final WardrobeAccess wardrobes;
    private final GarmentAccess garments;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Validator validator;
    private final Clock clock;

    public OutfitService(
            OutfitRepository outfits,
            WardrobeAccess wardrobes,
            GarmentAccess garments,
            JdbcClient jdbc,
            JsonMapper json,
            Validator validator,
            Clock clock) {
        this.outfits = outfits;
        this.wardrobes = wardrobes;
        this.garments = garments;
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public OutfitDetails create(ObjectNode request) {
        var input = parse(request);
        validate(input);
        return outfits.saveAndFlush(
                        new Outfit(wardrobes.currentWardrobeId(), input, clock.instant()))
                .details();
    }

    @Transactional
    public OutfitDetails get(UUID id) {
        return owned(id).details();
    }

    @Transactional
    public OutfitDetails patch(UUID id, ObjectNode request) {
        var current = locked(id);
        var version = request.get("version");
        if (version == null
                || !version.isIntegralNumber()
                || !version.canConvertToLong()
                || version.asLong() < 0)
            throw DomainException.invalid("A current version is required.");
        ObjectNode values = json.valueToTree(current.details().metadata());
        values.set("items", json.valueToTree(current.details().items()));
        request.properties()
                .forEach(
                        entry -> {
                            if (!entry.getKey().equals("version"))
                                values.set(entry.getKey(), entry.getValue());
                        });
        var input = parse(values);
        validate(input);
        current.update(input, version.asLong(), clock.instant());
        return outfits.saveAndFlush(current).details();
    }

    @Transactional
    public void delete(UUID id, long version) {
        var outfit = locked(id);
        outfit.verifyVersion(version);
        outfits.delete(outfit);
        outfits.flush();
    }

    @Transactional
    public OutfitDetails duplicate(UUID id, long version) {
        var original = locked(id);
        original.verifyVersion(version);
        var details = original.details();
        var metadata = details.metadata();
        String name = metadata.name();
        name = name.substring(0, Math.min(name.length(), 153)) + " (copy)";
        var copy =
                new OutfitMetadata(
                        name,
                        metadata.occasion(),
                        metadata.season(),
                        metadata.rating(),
                        metadata.tags(),
                        metadata.notes(),
                        false);
        var input = new OutfitInput(copy, details.items());
        validate(input);
        return outfits.saveAndFlush(
                        new Outfit(wardrobes.currentWardrobeId(), input, clock.instant()))
                .details();
    }

    @Override
    @Transactional
    public OutfitDetails forWear(UUID id, long version) {
        var outfit =
                outfits.lockForWear(id, wardrobes.currentWardrobeId())
                        .orElseThrow(() -> DomainException.notFound("Outfit"));
        outfit.verifyVersion(version);
        return outfit.details();
    }

    @Transactional
    public OutfitPage list(String q, boolean archived, String cursor, int limit) {
        UUID wardrobe = wardrobes.currentWardrobeId();
        String scope = wardrobe + ":" + archived + ":" + Objects.toString(q, "");
        var query =
                new StringBuilder(
                        "SELECT id FROM outfit WHERE wardrobe_id = :wardrobe AND archived = :archived");
        var parameters = new HashMap<String, Object>();
        parameters.put("wardrobe", wardrobe);
        parameters.put("archived", archived);
        if (q != null && !q.isBlank()) {
            query.append(" AND strpos(lower(name), lower(:q)) > 0");
            parameters.put("q", q.strip());
        }
        if (cursor != null) {
            var after = decode(cursor, scope);
            query.append(" AND (created_at, id) < (:after, :id)");
            parameters.put("after", java.sql.Timestamp.from(after.createdAt()));
            parameters.put("id", after.id());
        }
        query.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        parameters.put("limit", limit + 1);
        var ids = jdbc.sql(query.toString()).params(parameters).query(UUID.class).list();
        var result = ids.stream().limit(limit).map(id -> owned(id).details()).toList();
        String next = null;
        if (ids.size() > limit) {
            var last = result.getLast();
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    json.writeValueAsString(
                                                    new Cursor(last.createdAt(), last.id(), scope))
                                            .getBytes(StandardCharsets.UTF_8));
        }
        return new OutfitPage(result, next);
    }

    private Cursor decode(String value, String scope) {
        try {
            var cursor = json.readValue(Base64.getUrlDecoder().decode(value), Cursor.class);
            if (!scope.equals(cursor.scope()) || cursor.createdAt() == null || cursor.id() == null)
                throw new IllegalArgumentException();
            return cursor;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw DomainException.invalid("This outfit cursor is invalid for these filters.");
        }
    }

    private OutfitInput parse(ObjectNode request) {
        if (!FIELDS.containsAll(request.propertyNames()))
            throw DomainException.invalid("Unknown or read-only outfit field.");
        try {
            var metadata = request.deepCopy();
            if (!metadata.has("archived")) metadata.put("archived", false);
            var items = metadata.remove("items");
            if (items == null || !items.isArray())
                throw DomainException.invalid("An outfit item list is required.");
            return new OutfitInput(
                    json.treeToValue(metadata, OutfitMetadata.class),
                    json.readValue(items.toString(), new TypeReference<List<OutfitItem>>() {}));
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            throw DomainException.invalid("Check the outfit fields and try again.");
        }
    }

    private void validate(OutfitInput input) {
        if (!validator.validate(input).isEmpty())
            throw DomainException.invalid("Check the outfit fields and canvas positions.");
        var ids = input.items().stream().map(OutfitItem::garmentId).toList();
        if (new HashSet<>(ids).size() != ids.size())
            throw DomainException.invalid("Use each garment only once in an outfit.");
        garments.lockOwned(ids);
    }

    private Outfit owned(UUID id) {
        return outfits.findByIdAndWardrobeId(id, wardrobes.currentWardrobeId())
                .orElseThrow(() -> DomainException.notFound("Outfit"));
    }

    private Outfit locked(UUID id) {
        return outfits.lockForUpdate(id, wardrobes.currentWardrobeId())
                .orElseThrow(() -> DomainException.notFound("Outfit"));
    }

    private record Cursor(Instant createdAt, UUID id, String scope) {}
}
