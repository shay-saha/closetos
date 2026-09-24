package com.closetos.outfit.application;

import com.closetos.outfit.api.OutfitConnections;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SavedOutfitConnections implements OutfitConnections {
    private final JdbcClient jdbc;
    private final WardrobeAccess wardrobes;

    public SavedOutfitConnections(JdbcClient jdbc, WardrobeAccess wardrobes) {
        this.jdbc = jdbc;
        this.wardrobes = wardrobes;
    }

    @Override
    public Map<UUID, Integer> savedTogether(UUID source, List<UUID> candidates) {
        if (candidates.isEmpty()) return Map.of();
        return jdbc
                .sql(
                        """
                SELECT t.garment_id AS id, count(*)::integer AS count
                FROM outfit o JOIN outfit_item s ON s.outfit_id = o.id AND s.garment_id = :source
                    JOIN outfit_item t ON t.outfit_id = o.id AND t.garment_id <> :source
                    JOIN garment g ON g.id = t.garment_id AND g.wardrobe_id = o.wardrobe_id
                    JOIN garment selected ON selected.id = s.garment_id AND selected.wardrobe_id = o.wardrobe_id
                WHERE o.wardrobe_id = :wardrobe AND NOT o.archived AND t.garment_id IN (:candidates)
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
