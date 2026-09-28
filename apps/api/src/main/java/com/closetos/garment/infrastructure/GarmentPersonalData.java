package com.closetos.garment.infrastructure;

import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class GarmentPersonalData implements PersonalDataContributor {
    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "garments",
                "SELECT to_jsonb(g) - 'search_document' FROM garment g WHERE wardrobe_id = ? ORDER BY id",
                wardrobe);
    }
}
