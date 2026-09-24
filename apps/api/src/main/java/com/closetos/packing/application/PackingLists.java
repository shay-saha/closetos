package com.closetos.packing.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentPresenter;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.packing.api.PackingDetails;
import com.closetos.packing.api.PackingDetails.ItemStatus;
import com.closetos.packing.api.PackingSolution;
import com.closetos.packing.api.PackingTrip;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import jakarta.validation.Validator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
public class PackingLists {
    private static final Set<String> TRIP_FIELDS =
            Set.of("name", "startDate", "endDate", "locationText", "constraints");
    private static final Set<String> CONSTRAINT_FIELDS =
            Set.of(
                    "maximumGarments",
                    "weather",
                    "occasions",
                    "formalEvents",
                    "laundryEveryDays",
                    "maximumWearsBetweenLaundry",
                    "requiredGarments",
                    "excludedGarments");
    private static final Set<String> WEATHER_FIELDS =
            Set.of("minimumTemperatureC", "maximumTemperatureC", "assumptions", "season");
    private static final Set<String> OCCASION_FIELDS =
            Set.of("name", "occasionTag", "formality", "days");
    private static final Set<String> EVENT_FIELDS =
            Set.of("name", "date", "occasionTag", "formality");
    private final JdbcClient jdbc;
    private final WardrobeAccess wardrobes;
    private final GarmentAccess garments;
    private final GarmentPresenter presenter;
    private final PackingRules rules;
    private final PackingVerifier verifier;
    private final JsonMapper json;
    private final Validator validator;
    private final Clock clock;

    public PackingLists(
            JdbcClient jdbc,
            WardrobeAccess wardrobes,
            GarmentAccess garments,
            GarmentPresenter presenter,
            PackingRules rules,
            PackingVerifier verifier,
            JsonMapper json,
            Validator validator,
            Clock clock) {
        this.jdbc = jdbc;
        this.wardrobes = wardrobes;
        this.garments = garments;
        this.presenter = presenter;
        this.rules = rules;
        this.verifier = verifier;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
    }

    public PackingDetails create(ObjectNode request) {
        var trip = parse(request);
        validateOwnership(trip);
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO packing_list(id, wardrobe_id, name, start_date, end_date, location_text, constraints, created_at, updated_at)
                VALUES (:id, :wardrobe, :name, :start, :end, :location, CAST(:constraints AS jsonb), :now, :now)
                """)
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .param("name", trip.name())
                .param("start", trip.startDate())
                .param("end", trip.endDate())
                .param("location", trip.locationText())
                .param("constraints", json.writeValueAsString(trip.constraints()))
                .param("now", now())
                .update();
        return get(id);
    }

    public PackingDetails get(UUID id) {
        return detail(owned(id));
    }

    public PackingDetails patch(UUID id, ObjectNode request) {
        var current = locked(id);
        verifyVersion(current, version(request));
        var merged = (ObjectNode) json.valueToTree(current.trip());
        request.properties()
                .forEach(
                        field -> {
                            if (!field.getKey().equals("version"))
                                merged.set(field.getKey(), field.getValue());
                        });
        var trip = parse(merged);
        validateOwnership(trip);
        boolean invalidate =
                !current.trip().startDate().equals(trip.startDate())
                        || !current.trip().endDate().equals(trip.endDate())
                        || !current.trip().constraints().equals(trip.constraints());
        if (invalidate) requireUnpacked(id);
        jdbc.sql(
                        """
                UPDATE packing_list SET name = :name, start_date = :start, end_date = :end, location_text = :location,
                    constraints = CAST(:constraints AS jsonb), plan = CAST(:plan AS jsonb), manual_override = :manual,
                    garment_versions = CAST(:versions AS jsonb), explanations = CAST(:explanations AS jsonb),
                    version = version + 1, updated_at = :now
                WHERE id = :id AND wardrobe_id = :wardrobe
                """)
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .param("name", trip.name())
                .param("start", trip.startDate())
                .param("end", trip.endDate())
                .param("location", trip.locationText())
                .param("constraints", json.writeValueAsString(trip.constraints()))
                .param(
                        "plan",
                        invalidate || current.plan() == null
                                ? null
                                : json.writeValueAsString(current.plan()))
                .param("manual", !invalidate && current.manualOverride())
                .param(
                        "explanations",
                        json.writeValueAsString(invalidate ? List.of() : current.explanations()))
                .param(
                        "versions",
                        json.writeValueAsString(invalidate ? Map.of() : current.garmentVersions()))
                .param("now", now())
                .update();
        if (invalidate) deleteItems(id);
        return get(id);
    }

    public void delete(UUID id, long version) {
        verifyVersion(locked(id), version);
        requireUnpacked(id);
        jdbc.sql("DELETE FROM packing_list WHERE id = :id AND wardrobe_id = :wardrobe")
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .update();
    }

    public PackingDetails.Page list(String query, String cursor, int limit) {
        UUID wardrobe = wardrobes.currentWardrobeId();
        String q = query == null ? "" : query.strip();
        String scope = wardrobe + ":" + q;
        var parameters = new HashMap<String, Object>();
        parameters.put("wardrobe", wardrobe);
        parameters.put("q", q);
        var sql =
                new StringBuilder(
                        """
                SELECT p.id, p.name, p.start_date, p.end_date, p.location_text, p.version, p.created_at,
                    p.plan ->> 'status' AS plan_status,
                    (SELECT count(*) FROM packing_item i WHERE i.packing_list_id = p.id AND i.wardrobe_id = p.wardrobe_id) AS item_count,
                    (SELECT count(*) FROM packing_item i WHERE i.packing_list_id = p.id AND i.wardrobe_id = p.wardrobe_id AND i.status = 'PACKED') AS packed_count
                FROM packing_list p WHERE p.wardrobe_id = :wardrobe AND strpos(lower(p.name), lower(:q)) > 0
                """);
        if (cursor != null) {
            var after = decode(cursor, scope);
            sql.append(" AND (p.created_at, p.id) < (:after, :id)");
            parameters.put("after", Timestamp.from(after.createdAt()));
            parameters.put("id", after.id());
        }
        sql.append(" ORDER BY p.created_at DESC, p.id DESC LIMIT :limit");
        parameters.put("limit", limit + 1);
        var found =
                jdbc.sql(sql.toString())
                        .params(parameters)
                        .query(
                                (rs, row) ->
                                        new PackingDetails.Summary(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("name"),
                                                rs.getDate("start_date").toLocalDate(),
                                                rs.getDate("end_date").toLocalDate(),
                                                rs.getString("location_text"),
                                                rs.getLong("version"),
                                                rs.getString("plan_status") == null
                                                        ? null
                                                        : PackingSolution.Status.valueOf(
                                                                rs.getString("plan_status")),
                                                rs.getInt("item_count"),
                                                rs.getInt("packed_count"),
                                                rs.getTimestamp("created_at").toInstant()))
                        .list();
        var items = found.stream().limit(limit).toList();
        String next = null;
        if (found.size() > limit) {
            var last = items.getLast();
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    json.writeValueAsBytes(
                                            new Cursor(last.createdAt(), last.id(), scope)));
        }
        return new PackingDetails.Page(items, next);
    }

    public PackingSnapshot snapshot(UUID id, long version) {
        var current = locked(id);
        verifyVersion(current, version);
        requireUnpacked(id);
        var owned = garments.ownedDetails(garments.ownedIds());
        validateOwnership(
                current.trip(), owned.stream().map(GarmentDetails::id).collect(Collectors.toSet()));
        return new PackingSnapshot(id, version, rules.prepare(current.trip(), owned), owned);
    }

    public PackingDetails saveSolution(
            PackingSnapshot snapshot, PackingSolution solution, boolean manual) {
        verifyVersion(locked(snapshot.id()), snapshot.version());
        requireUnpacked(snapshot.id());
        var ids = garments.ownedIds();
        var before =
                snapshot.wardrobe().stream()
                        .collect(Collectors.toMap(GarmentDetails::id, PackingLists::stamp));
        if (!before.keySet().equals(Set.copyOf(ids))) throw DomainException.conflict();
        garments.lockOwned(ids);
        var fresh = garments.ownedDetails(ids);
        var after =
                fresh.stream().collect(Collectors.toMap(GarmentDetails::id, PackingLists::stamp));
        if (!before.equals(after)) throw DomainException.conflict();
        verifier.verify(snapshot.preparation().problem(), solution);
        deleteItems(snapshot.id());
        var versions = new HashMap<UUID, Long>();
        var lookup =
                fresh.stream().collect(Collectors.toMap(GarmentDetails::id, Function.identity()));
        for (UUID garment : solution.selectedGarments()) {
            jdbc.sql(
                            "INSERT INTO packing_item(packing_list_id, wardrobe_id, garment_id) VALUES (:list, :wardrobe, :garment)")
                    .param("list", snapshot.id())
                    .param("wardrobe", wardrobes.currentWardrobeId())
                    .param("garment", garment)
                    .update();
            versions.put(garment, lookup.get(garment).version());
        }
        jdbc.sql(
                        """
                UPDATE packing_list SET plan = CAST(:plan AS jsonb), manual_override = :manual,
                    garment_versions = CAST(:versions AS jsonb), explanations = CAST(:explanations AS jsonb),
                    version = version + 1, updated_at = :now
                WHERE id = :id AND wardrobe_id = :wardrobe
                """)
                .param("plan", json.writeValueAsString(solution))
                .param("manual", manual)
                .param(
                        "explanations",
                        json.writeValueAsString(
                                verifier.explanations(
                                        snapshot.preparation().problem(), solution, manual)))
                .param("versions", json.writeValueAsString(versions))
                .param("now", now())
                .param("id", snapshot.id())
                .param("wardrobe", wardrobes.currentWardrobeId())
                .update();
        return get(snapshot.id());
    }

    public PackingDetails packed(
            UUID id, UUID garmentId, long listVersion, long garmentVersion, boolean packed) {
        var current = locked(id);
        verifyVersion(current, listVersion);
        var statuses = itemStatuses(id);
        if (!statuses.containsKey(garmentId)) throw DomainException.notFound("Packing item");
        garments.lockOwned(List.of(garmentId));
        var garment = garments.owned(garmentId);
        if (garment.version() != garmentVersion) throw DomainException.conflict();
        if (packed
                && statuses.get(garmentId) == ItemStatus.PACKED
                && garment.status() == GarmentStatus.PACKED) return get(id);
        if (!packed && statuses.get(garmentId) == ItemStatus.TO_PACK) {
            if (garment.status() == GarmentStatus.AVAILABLE) return get(id);
            throw new DomainException(
                    409,
                    "PACKING_ITEM_UNAVAILABLE",
                    "This list did not mark this piece packed. Review the list that packed it before unpacking.");
        }
        if (packed) {
            var all = garments.ownedDetails(new ArrayList<>(statuses.keySet()));
            if (stale(current, statuses, all))
                throw new DomainException(
                        409,
                        "PACKING_STALE",
                        "This capsule changed. Refresh or regenerate it before packing another piece.");
            if (garment.status() != GarmentStatus.AVAILABLE
                    || garment.processingStatus() != ProcessingStatus.READY)
                throw new DomainException(
                        409,
                        "PACKING_ITEM_UNAVAILABLE",
                        "Only a reviewed AVAILABLE piece can be marked packed.");
        } else if (garment.status() != GarmentStatus.PACKED
                && garment.status() != GarmentStatus.AVAILABLE) {
            throw new DomainException(
                    409,
                    "PACKING_ITEM_UNAVAILABLE",
                    "This piece has another availability status. Review it before unpacking.");
        }
        GarmentStatus desired = packed ? GarmentStatus.PACKED : GarmentStatus.AVAILABLE;
        var changed =
                garment.status() == desired
                        ? garment
                        : garments.status(garmentId, desired, garmentVersion);
        var versions = new HashMap<>(current.garmentVersions());
        versions.put(garmentId, changed.version());
        jdbc.sql(
                        "UPDATE packing_item SET status = :status WHERE packing_list_id = :list AND wardrobe_id = :wardrobe AND garment_id = :garment")
                .param("status", packed ? "PACKED" : "TO_PACK")
                .param("list", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .param("garment", garmentId)
                .update();
        jdbc.sql(
                        "UPDATE packing_list SET garment_versions = CAST(:versions AS jsonb), version = version + 1, updated_at = :now WHERE id = :id AND wardrobe_id = :wardrobe")
                .param("versions", json.writeValueAsString(versions))
                .param("now", now())
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .update();
        return get(id);
    }

    private PackingDetails detail(Row row) {
        var statuses = itemStatuses(row.id());
        var pieces =
                garments.ownedDetails(new ArrayList<>(statuses.keySet())).stream()
                        .sorted(java.util.Comparator.comparing(piece -> piece.id().toString()))
                        .toList();
        boolean stale = stale(row, statuses, pieces);
        var presented = presenter.page(new GarmentPage(pieces, null)).items();
        return new PackingDetails(
                row.id(),
                row.trip(),
                row.version(),
                row.manualOverride(),
                row.plan(),
                stale,
                stale
                        ? List.of(
                                "The capsule no longer matches the current wardrobe. Review or regenerate it before packing more pieces.")
                        : row.explanations(),
                presented.stream()
                        .map(piece -> new PackingDetails.Item(piece, statuses.get(piece.id())))
                        .toList(),
                row.createdAt(),
                row.updatedAt());
    }

    private boolean stale(Row row, Map<UUID, ItemStatus> statuses, List<GarmentDetails> pieces) {
        if (row.plan() == null || !row.plan().hasSolution()) return false;
        if (!Set.copyOf(row.plan().selectedGarments()).equals(statuses.keySet())
                || pieces.size() != statuses.size()) return true;
        return pieces.stream()
                .anyMatch(
                        piece ->
                                !java.util.Objects.equals(
                                                row.garmentVersions().get(piece.id()),
                                                piece.version())
                                        || piece.processingStatus() != ProcessingStatus.READY
                                        || piece.status()
                                                != (statuses.get(piece.id()) == ItemStatus.PACKED
                                                        ? GarmentStatus.PACKED
                                                        : GarmentStatus.AVAILABLE));
    }

    private Map<UUID, ItemStatus> itemStatuses(UUID id) {
        var result = new HashMap<UUID, ItemStatus>();
        jdbc.sql(
                        "SELECT garment_id, status FROM packing_item WHERE packing_list_id = :id AND wardrobe_id = :wardrobe ORDER BY garment_id")
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .query(
                        (rs, row) -> {
                            result.put(
                                    rs.getObject("garment_id", UUID.class),
                                    ItemStatus.valueOf(rs.getString("status")));
                            return rs.getObject("garment_id", UUID.class);
                        })
                .list();
        return result;
    }

    private Row owned(UUID id) {
        return jdbc.sql("SELECT * FROM packing_list WHERE id = :id AND wardrobe_id = :wardrobe")
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .query(this::map)
                .optional()
                .orElseThrow(() -> DomainException.notFound("Packing list"));
    }

    private Row locked(UUID id) {
        jdbc.sql(
                        "SELECT id FROM packing_list WHERE id = :id AND wardrobe_id = :wardrobe FOR UPDATE")
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> DomainException.notFound("Packing list"));
        return owned(id);
    }

    private Row map(ResultSet rs, int index) throws SQLException {
        var trip =
                new PackingTrip(
                        rs.getString("name"),
                        rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate(),
                        rs.getString("location_text"),
                        json.readValue(rs.getString("constraints"), PackingTrip.Constraints.class));
        var versions = new HashMap<UUID, Long>();
        json.readTree(rs.getString("garment_versions"))
                .properties()
                .forEach(
                        field ->
                                versions.put(
                                        UUID.fromString(field.getKey()),
                                        field.getValue().asLong()));
        return new Row(
                rs.getObject("id", UUID.class),
                trip,
                rs.getLong("version"),
                rs.getBoolean("manual_override"),
                rs.getString("plan") == null
                        ? null
                        : json.readValue(rs.getString("plan"), PackingSolution.class),
                versions,
                json.readValue(
                        rs.getString("explanations"),
                        json.getTypeFactory().constructCollectionType(List.class, String.class)),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private PackingTrip parse(ObjectNode request) {
        fields(request, TRIP_FIELDS);
        var constraints = object(request.get("constraints"), "Trip constraints");
        fields(constraints, CONSTRAINT_FIELDS);
        fields(object(constraints.get("weather"), "Weather constraints"), WEATHER_FIELDS);
        for (String name : List.of("occasions", "formalEvents")) {
            var values = constraints.get(name);
            if (values == null && name.equals("formalEvents")) continue;
            if (values == null || !values.isArray())
                throw DomainException.invalid(name + " must be an array.");
            for (var value : values)
                fields(
                        object(value, name),
                        name.equals("occasions") ? OCCASION_FIELDS : EVENT_FIELDS);
        }
        PackingTrip trip;
        try {
            trip = json.treeToValue(request, PackingTrip.class);
        } catch (JacksonException exception) {
            throw DomainException.invalid("Check the trip dates and constraints and try again.");
        }
        if (!validator.validate(trip).isEmpty())
            throw DomainException.invalid("Check the trip labels, dates, and constraint limits.");
        PackingRules.validate(trip);
        return trip;
    }

    private ObjectNode object(tools.jackson.databind.JsonNode value, String label) {
        if (!(value instanceof ObjectNode object))
            throw DomainException.invalid(label + " must be an object.");
        return object;
    }

    private void fields(ObjectNode node, Set<String> fields) {
        node.properties()
                .forEach(
                        field -> {
                            if (!fields.contains(field.getKey()))
                                throw DomainException.invalid(
                                        "Unknown or read-only field: " + field.getKey());
                        });
    }

    private void validateOwnership(PackingTrip trip) {
        validateOwnership(trip, Set.copyOf(garments.ownedIds()));
    }

    private void validateOwnership(PackingTrip trip, Set<UUID> owned) {
        if (!owned.containsAll(trip.constraints().requiredGarments())
                || !owned.containsAll(trip.constraints().excludedGarments()))
            throw DomainException.notFound("Garment");
    }

    private void requireUnpacked(UUID id) {
        if (itemStatuses(id).containsValue(ItemStatus.PACKED))
            throw new DomainException(
                    409,
                    "PACKING_ALREADY_PACKED",
                    "Unpack this list's pieces before changing its constraints, regenerating, or deleting it.");
    }

    private void deleteItems(UUID id) {
        jdbc.sql("DELETE FROM packing_item WHERE packing_list_id = :id AND wardrobe_id = :wardrobe")
                .param("id", id)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .update();
    }

    private void verifyVersion(Row row, long version) {
        if (row.version() != version) throw DomainException.conflict();
    }

    private long version(ObjectNode request) {
        var value = request.get("version");
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.asLong() < 0)
            throw DomainException.invalid("A current packing list version is required.");
        return value.asLong();
    }

    private Cursor decode(String encoded, String scope) {
        try {
            var cursor = json.readValue(Base64.getUrlDecoder().decode(encoded), Cursor.class);
            if (cursor.createdAt() == null || cursor.id() == null || !scope.equals(cursor.scope()))
                throw new IllegalArgumentException();
            return cursor;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw DomainException.invalid("This cursor does not match the packing list search.");
        }
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static Stamp stamp(GarmentDetails piece) {
        return new Stamp(
                piece.version(),
                piece.status(),
                piece.processingStatus(),
                piece.metadata(),
                piece.wearCount(),
                piece.lastWornAt());
    }

    private record Stamp(
            long version,
            GarmentStatus status,
            ProcessingStatus processing,
            com.closetos.garment.api.GarmentMetadata metadata,
            int wearCount,
            java.time.LocalDate lastWornAt) {}

    private record Cursor(Instant createdAt, UUID id, String scope) {}

    private record Row(
            UUID id,
            PackingTrip trip,
            long version,
            boolean manualOverride,
            PackingSolution plan,
            Map<UUID, Long> garmentVersions,
            List<String> explanations,
            Instant createdAt,
            Instant updatedAt) {}
}
