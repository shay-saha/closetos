package com.closetos.wear.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.identity.api.IdentityAccess;
import com.closetos.outfit.api.OutfitAccess;
import com.closetos.outfit.api.OutfitItem;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import com.closetos.wear.api.WearDetails;
import com.closetos.wear.api.WearPage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WearService {
    private final JdbcClient jdbc;
    private final GarmentAccess garments;
    private final OutfitAccess outfits;
    private final WardrobeAccess wardrobes;
    private final IdentityAccess identity;
    private final JsonMapper json;
    private static final String COLUMNS =
            "id, outfit_id, outfit_name, worn_on, notes, context, created_at";

    public WearService(
            JdbcClient jdbc,
            GarmentAccess garments,
            OutfitAccess outfits,
            WardrobeAccess wardrobes,
            IdentityAccess identity,
            JsonMapper json) {
        this.jdbc = jdbc;
        this.garments = garments;
        this.outfits = outfits;
        this.wardrobes = wardrobes;
        this.identity = identity;
        this.json = json;
    }

    @Transactional
    public WearDetails log(
            UUID key,
            UUID outfitId,
            long outfitVersion,
            List<UUID> garmentIds,
            LocalDate wornOn,
            String notes,
            String context) {
        UUID wardrobe = wardrobes.currentWardrobeId();
        List<UUID> requested = garmentIds.stream().distinct().sorted().toList();
        String fingerprint =
                fingerprint(
                        new Command(outfitId, outfitVersion, requested, wornOn, notes, context));
        // Serialize retries for this owner and key, including concurrent first submissions.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", wardrobe + ":" + key)
                .query(Object.class)
                .single();
        var prior =
                jdbc.sql(
                                "SELECT id, request_fingerprint, deleted_at IS NOT NULL AS deleted FROM wear_event WHERE wardrobe_id = :wardrobe AND idempotency_key = :key")
                        .param("wardrobe", wardrobe)
                        .param("key", key)
                        .query(Prior.class)
                        .optional();
        if (prior.isPresent()) {
            var replay = prior.get();
            if (!fingerprint.equals(replay.requestFingerprint()) || replay.deleted())
                throw new DomainException(
                        409,
                        "IDEMPOTENCY_CONFLICT",
                        "This wear key was already used. Start a new entry for a different wear record.");
            return details(replay.id(), wardrobe);
        }
        String outfitName = null;
        List<UUID> included = requested;
        if (outfitId != null) {
            var outfit = outfits.forWear(outfitId, outfitVersion);
            outfitName = outfit.metadata().name();
            included =
                    outfit.items().stream().map(OutfitItem::garmentId).distinct().sorted().toList();
        }
        if (included.isEmpty())
            throw DomainException.invalid("Choose at least one garment before logging wear.");
        garments.lockOwned(included);
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO wear_event (id, user_id, wardrobe_id, outfit_id, outfit_name, worn_on, notes, context, idempotency_key, request_fingerprint)
                VALUES (:id, :user, :wardrobe, :outfit, :name, :date, :notes, :context, :key, :fingerprint)
                """)
                .param("id", id)
                .param("user", identity.currentUserId())
                .param("wardrobe", wardrobe)
                .param("outfit", outfitId)
                .param("name", outfitName)
                .param("date", wornOn)
                .param("notes", notes)
                .param("context", context)
                .param("key", key)
                .param("fingerprint", fingerprint)
                .update();
        for (UUID garment : included)
            jdbc.sql(
                            "INSERT INTO wear_event_garment (wear_event_id, garment_id) VALUES (:event, :garment)")
                    .param("event", id)
                    .param("garment", garment)
                    .update();
        recalculate(included);
        return details(id, wardrobe);
    }

    @Transactional
    public void remove(UUID id) {
        UUID wardrobe = wardrobes.currentWardrobeId();
        var found =
                jdbc.sql(
                                "SELECT id FROM wear_event WHERE id = :id AND wardrobe_id = :wardrobe AND deleted_at IS NULL FOR UPDATE")
                        .param("id", id)
                        .param("wardrobe", wardrobe)
                        .query(UUID.class)
                        .optional();
        if (found.isEmpty()) throw DomainException.notFound("Wear entry");
        var ids = included(id);
        garments.lockOwned(ids);
        jdbc.sql(
                        "UPDATE wear_event SET deleted_at = now() WHERE id = :id AND wardrobe_id = :wardrobe")
                .param("id", id)
                .param("wardrobe", wardrobe)
                .update();
        recalculate(ids);
    }

    @Transactional
    public void repair() {
        var ids = garments.ownedIds();
        garments.lockOwned(ids);
        recalculate(ids);
    }

    @Transactional
    public WearDetails get(UUID id) {
        return details(id, wardrobes.currentWardrobeId());
    }

    private void recalculate(List<UUID> ids) {
        for (UUID id : ids) {
            var statistics =
                    jdbc.sql(
                                    """
                SELECT count(*) AS count, max(e.worn_on) AS last_worn_on
                FROM wear_event_garment g JOIN wear_event e ON e.id = g.wear_event_id
                WHERE g.garment_id = :id AND e.wardrobe_id = :wardrobe AND e.deleted_at IS NULL
                """)
                            .param("id", id)
                            .param("wardrobe", wardrobes.currentWardrobeId())
                            .query(Statistics.class)
                            .single();
            garments.updateWearStatistics(id, statistics.count(), statistics.lastWornOn());
        }
    }

    @Transactional
    public WearPage list(UUID garment, UUID outfit, String cursor, int limit) {
        UUID wardrobe = wardrobes.currentWardrobeId();
        if (garment != null) garments.owned(garment);
        String scope = wardrobe + ":" + garment + ":" + outfit;
        var parameters = new HashMap<String, Object>();
        parameters.put("wardrobe", wardrobe);
        parameters.put("limit", limit + 1);
        var query =
                new StringBuilder(
                        "SELECT "
                                + COLUMNS
                                + " FROM wear_event e WHERE e.wardrobe_id = :wardrobe AND e.deleted_at IS NULL");
        if (garment != null) {
            query.append(
                    " AND EXISTS (SELECT 1 FROM wear_event_garment g WHERE g.wear_event_id = e.id AND g.garment_id = :garment)");
            parameters.put("garment", garment);
        }
        if (outfit != null) {
            query.append(" AND e.outfit_id = :outfit");
            parameters.put("outfit", outfit);
        }
        if (cursor != null) {
            var after = decode(cursor, scope);
            query.append(" AND (e.worn_on, e.created_at, e.id) < (:date, :after, :id)");
            parameters.put("date", after.wornOn());
            parameters.put("after", java.sql.Timestamp.from(after.createdAt()));
            parameters.put("id", after.id());
        }
        query.append(" ORDER BY e.worn_on DESC, e.created_at DESC, e.id DESC LIMIT :limit");
        var found = jdbc.sql(query.toString()).params(parameters).query(this::read).list();
        var result = found.stream().limit(limit).toList();
        String next = null;
        if (found.size() > limit) {
            var last = result.getLast();
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    json.writeValueAsString(
                                                    new Cursor(
                                                            last.wornOn(),
                                                            last.createdAt(),
                                                            last.id(),
                                                            scope))
                                            .getBytes(StandardCharsets.UTF_8));
        }
        return new WearPage(result, next);
    }

    private WearDetails details(UUID id, UUID wardrobe) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM wear_event WHERE id = :id AND wardrobe_id = :wardrobe AND deleted_at IS NULL")
                .param("id", id)
                .param("wardrobe", wardrobe)
                .query(this::read)
                .optional()
                .orElseThrow(() -> DomainException.notFound("Wear entry"));
    }

    private WearDetails read(ResultSet row, int index) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        return new WearDetails(
                id,
                row.getObject("outfit_id", UUID.class),
                row.getString("outfit_name"),
                row.getObject("worn_on", LocalDate.class),
                row.getString("notes"),
                row.getString("context"),
                included(id),
                row.getTimestamp("created_at").toInstant());
    }

    private List<UUID> included(UUID id) {
        return jdbc.sql(
                        "SELECT garment_id FROM wear_event_garment WHERE wear_event_id = :id ORDER BY garment_id")
                .param("id", id)
                .query(UUID.class)
                .list();
    }

    private String fingerprint(Command command) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsString(command)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Cursor decode(String value, String scope) {
        try {
            var cursor = json.readValue(Base64.getUrlDecoder().decode(value), Cursor.class);
            if (!scope.equals(cursor.scope())
                    || cursor.id() == null
                    || cursor.createdAt() == null
                    || cursor.wornOn() == null) throw new IllegalArgumentException();
            return cursor;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw DomainException.invalid("This wear history cursor is invalid for these filters.");
        }
    }

    private record Command(
            UUID outfitId,
            long outfitVersion,
            List<UUID> garmentIds,
            LocalDate wornOn,
            String notes,
            String context) {}

    private record Prior(UUID id, String requestFingerprint, boolean deleted) {}

    private record Statistics(int count, LocalDate lastWornOn) {}

    private record Cursor(LocalDate wornOn, Instant createdAt, UUID id, String scope) {}
}
