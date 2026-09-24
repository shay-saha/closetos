package com.closetos.wear.application;

import com.closetos.wardrobe.api.WardrobeAccess;
import com.closetos.wear.api.WearConnections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class RecordedWearConnections implements WearConnections {
    private final JdbcClient jdbc;
    private final WardrobeAccess wardrobes;

    public RecordedWearConnections(JdbcClient jdbc, WardrobeAccess wardrobes) {
        this.jdbc = jdbc;
        this.wardrobes = wardrobes;
    }

    @Override
    public Map<UUID, Integer> wornTogether(UUID source, List<UUID> candidates) {
        if (candidates.isEmpty()) return Map.of();
        return jdbc
                .sql(
                        """
                SELECT t.garment_id AS id, count(*)::integer AS count
                FROM wear_event e JOIN wear_event_garment s ON s.wear_event_id = e.id AND s.garment_id = :source
                    JOIN wear_event_garment t ON t.wear_event_id = e.id AND t.garment_id <> :source
                    JOIN garment g ON g.id = t.garment_id AND g.wardrobe_id = e.wardrobe_id
                    JOIN garment selected ON selected.id = s.garment_id AND selected.wardrobe_id = e.wardrobe_id
                WHERE e.wardrobe_id = :wardrobe AND e.deleted_at IS NULL AND t.garment_id IN (:candidates)
                GROUP BY t.garment_id
                """)
                .param("wardrobe", wardrobes.currentWardrobeId())
                .param("source", source)
                .param("candidates", candidates)
                .query(Connection.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Connection::id, Connection::count));
    }

    private record Connection(UUID id, int count) {}
}
