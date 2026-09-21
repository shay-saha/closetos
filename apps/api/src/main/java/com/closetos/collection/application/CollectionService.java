package com.closetos.collection.application;

import com.closetos.collection.api.CollectionDetails;
import com.closetos.collection.api.CollectionPage;
import com.closetos.collection.api.CollectionType;
import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentFilter;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentSelection;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CollectionService {
    private static final Set<String> FIELDS =
            Set.of("name", "type", "queryDefinition", "garmentIds");
    private static final String SELECT =
            """
            SELECT c.*, ARRAY(SELECT cg.garment_id FROM collection_garment cg
                WHERE cg.collection_id = c.id AND cg.wardrobe_id = c.wardrobe_id
                ORDER BY cg.garment_id) AS garment_ids FROM collection c
            """;
    private final JdbcClient jdbc;
    private final WardrobeAccess wardrobes;
    private final GarmentAccess garments;
    private final JsonMapper json;
    private final Clock clock;

    public CollectionService(
            JdbcClient jdbc,
            WardrobeAccess wardrobes,
            GarmentAccess garments,
            JsonMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.wardrobes = wardrobes;
        this.garments = garments;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public CollectionDetails create(ObjectNode request) {
        var input = parse(request);
        validate(input);
        UUID id = UUID.randomUUID();
        UUID wardrobe = wardrobes.currentWardrobeId();
        var now = Timestamp.from(clock.instant());
        jdbc.sql(
                        """
                INSERT INTO collection(id, wardrobe_id, name, type, query_definition, created_at, updated_at)
                VALUES (:id, :wardrobe, :name, :type, CAST(:query AS jsonb), :now, :now)
                """)
                .param("id", id)
                .param("wardrobe", wardrobe)
                .param("name", input.name())
                .param("type", input.type().name())
                .param("query", serialized(input.query()))
                .param("now", now)
                .update();
        insertMembers(id, wardrobe, input.garments());
        return owned(id);
    }

    @Transactional
    public CollectionDetails get(UUID id) {
        return owned(id);
    }

    @Transactional
    public CollectionDetails patch(UUID id, ObjectNode request) {
        var current = locked(id);
        var value = request.get("version");
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.asLong() < 0)
            throw DomainException.invalid("A current version is required.");
        verify(current, value.asLong());
        var merged =
                json.createObjectNode()
                        .put("name", current.name())
                        .put("type", current.type().name());
        merged.set("queryDefinition", json.valueToTree(current.queryDefinition()));
        merged.set("garmentIds", json.valueToTree(current.garmentIds()));
        request.properties()
                .forEach(
                        entry -> {
                            if (!entry.getKey().equals("version"))
                                merged.set(entry.getKey(), entry.getValue());
                        });
        var input = parse(merged);
        validate(input);
        deleteMembers(id);
        jdbc.sql(
                        """
                UPDATE collection SET name = :name, type = :type, query_definition = CAST(:query AS jsonb),
                    version = version + 1, updated_at = :now WHERE id = :id AND wardrobe_id = :wardrobe
                """)
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .param("name", input.name())
                .param("type", input.type().name())
                .param("query", serialized(input.query()))
                .param("now", Timestamp.from(clock.instant()))
                .update();
        insertMembers(id, wardrobes.currentWardrobeId(), input.garments());
        return owned(id);
    }

    @Transactional
    public void delete(UUID id, long version) {
        verify(locked(id), version);
        jdbc.sql("DELETE FROM collection WHERE id = :id AND wardrobe_id = :wardrobe")
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .update();
    }

    @Transactional
    public GarmentPage garments(UUID id, GarmentFilter filter) {
        var collection = owned(id);
        return garments.select(
                filter,
                new GarmentSelection(
                        id + ":" + collection.version(),
                        collection.queryDefinition(),
                        collection.type() == CollectionType.MANUAL
                                ? collection.garmentIds()
                                : null));
    }

    @Transactional
    public CollectionPage list(String q, CollectionType type, String cursor, int limit) {
        UUID wardrobe = wardrobes.currentWardrobeId();
        String scope = wardrobe + ":" + type + ":" + Objects.toString(q, "");
        var parameters = new HashMap<String, Object>();
        parameters.put("wardrobe", wardrobe);
        var sql = new StringBuilder(SELECT + " WHERE c.wardrobe_id = :wardrobe");
        if (q != null && !q.isBlank()) {
            sql.append(" AND strpos(lower(c.name), lower(:q)) > 0");
            parameters.put("q", q.strip());
        }
        if (type != null) {
            sql.append(" AND c.type = :type");
            parameters.put("type", type.name());
        }
        if (cursor != null) {
            var after = decode(cursor, scope);
            sql.append(" AND (c.created_at, c.id) < (:after, :id)");
            parameters.put("after", Timestamp.from(after.createdAt()));
            parameters.put("id", after.id());
        }
        sql.append(" ORDER BY c.created_at DESC, c.id DESC LIMIT :limit");
        parameters.put("limit", limit + 1);
        var result = jdbc.sql(sql.toString()).params(parameters).query(this::map).list();
        var items = result.stream().limit(limit).toList();
        String next = null;
        if (result.size() > limit) {
            var last = items.getLast();
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    json.writeValueAsBytes(
                                            new Cursor(last.createdAt(), last.id(), scope)));
        }
        return new CollectionPage(items, next);
    }

    private CollectionDetails owned(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id AND c.wardrobe_id = :wardrobe")
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .query(this::map)
                .optional()
                .orElseThrow(() -> DomainException.notFound("Collection"));
    }

    private CollectionDetails locked(UUID id) {
        jdbc.sql("SELECT id FROM collection WHERE id = :id AND wardrobe_id = :wardrobe FOR UPDATE")
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> DomainException.notFound("Collection"));
        return owned(id);
    }

    private CollectionDetails map(ResultSet rs, int row) throws SQLException {
        var ids = new ArrayList<UUID>();
        var array = rs.getArray("garment_ids");
        try {
            for (Object id : (Object[]) array.getArray()) ids.add((UUID) id);
        } finally {
            array.free();
        }
        String rules = rs.getString("query_definition");
        return new CollectionDetails(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                CollectionType.valueOf(rs.getString("type")),
                rules == null ? null : (ObjectNode) json.readTree(rules),
                List.copyOf(ids),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Input parse(ObjectNode request) {
        if (!FIELDS.containsAll(request.propertyNames())
                || !request.path("name").isString()
                || request.path("name").asText().isBlank()
                || request.path("name").asText().length() > 160
                || !request.path("type").isString()) throw invalid();
        try {
            CollectionType type = CollectionType.valueOf(request.get("type").asText());
            var query = request.get("queryDefinition");
            if (query != null && !query.isNull() && !query.isObject()) throw invalid();
            ObjectNode definition = query == null || query.isNull() ? null : (ObjectNode) query;
            var rawIds = request.get("garmentIds");
            List<UUID> ids = new ArrayList<>();
            if (rawIds != null) {
                if (!rawIds.isArray() || rawIds.size() > 5000) throw invalid();
                for (var id : rawIds) {
                    if (!id.isString() || id.asText().length() != 36) throw invalid();
                    ids.add(UUID.fromString(id.asText()));
                }
            }
            if (new HashSet<>(ids).size() != ids.size()
                    || (type == CollectionType.MANUAL && definition != null)
                    || (type == CollectionType.SMART && (definition == null || !ids.isEmpty())))
                throw invalid();
            return new Input(
                    request.get("name").asText().strip(),
                    type,
                    definition,
                    ids.stream().sorted().toList());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private void validate(Input input) {
        if (input.type() == CollectionType.SMART) garments.validateSmartQuery(input.query());
        else garments.lockOwned(input.garments());
    }

    private void insertMembers(UUID id, UUID wardrobe, List<UUID> ids) {
        if (ids.isEmpty()) return;
        jdbc.sql(
                        """
                INSERT INTO collection_garment(collection_id, garment_id, wardrobe_id)
                SELECT :id, garment_id, :wardrobe FROM unnest(CAST(:ids AS uuid[])) AS selected(garment_id)
                """)
                .param("id", id)
                .param("wardrobe", wardrobe)
                .param(
                        "ids",
                        "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}")
                .update();
    }

    private void deleteMembers(UUID id) {
        jdbc.sql(
                        "DELETE FROM collection_garment WHERE collection_id = :id AND wardrobe_id = :wardrobe")
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .update();
    }

    private String serialized(ObjectNode node) {
        return node == null ? null : node.toString();
    }

    private void verify(CollectionDetails current, long version) {
        if (current.version() != version) throw DomainException.conflict();
    }

    private Cursor decode(String value, String scope) {
        try {
            var cursor = json.readValue(Base64.getUrlDecoder().decode(value), Cursor.class);
            if (!scope.equals(cursor.scope()) || cursor.createdAt() == null || cursor.id() == null)
                throw new IllegalArgumentException();
            return cursor;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw DomainException.invalid("This collection cursor is invalid for these filters.");
        }
    }

    private static DomainException invalid() {
        return DomainException.invalid(
                "A collection needs a name, a MANUAL or SMART type, and either owned garments or valid rules.");
    }

    private record Input(String name, CollectionType type, ObjectNode query, List<UUID> garments) {}

    private record Cursor(Instant createdAt, UUID id, String scope) {}
}
