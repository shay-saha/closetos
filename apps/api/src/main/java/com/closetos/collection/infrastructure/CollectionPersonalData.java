package com.closetos.collection.infrastructure;

import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class CollectionPersonalData implements PersonalDataContributor {
    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "collections",
                "SELECT to_jsonb(c) FROM collection c WHERE wardrobe_id = ? ORDER BY id",
                wardrobe);
        writer.rows(
                "collectionGarments",
                "SELECT to_jsonb(g) FROM collection_garment g WHERE wardrobe_id = ? ORDER BY collection_id, garment_id",
                wardrobe);
    }
}
