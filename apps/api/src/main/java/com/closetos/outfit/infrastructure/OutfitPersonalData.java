package com.closetos.outfit.infrastructure;

import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class OutfitPersonalData implements PersonalDataContributor {
    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "outfits",
                "SELECT to_jsonb(o) FROM outfit o WHERE wardrobe_id = ? ORDER BY id",
                wardrobe);
        writer.rows(
                "outfitItems",
                "SELECT to_jsonb(i) FROM outfit_item i JOIN outfit o ON o.id = i.outfit_id WHERE o.wardrobe_id = ? ORDER BY i.outfit_id, i.garment_id",
                wardrobe);
    }
}
