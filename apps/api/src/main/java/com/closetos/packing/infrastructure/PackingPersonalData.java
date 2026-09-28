package com.closetos.packing.infrastructure;

import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PackingPersonalData implements PersonalDataContributor {
    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "packingLists",
                "SELECT to_jsonb(p) FROM packing_list p WHERE wardrobe_id = ? ORDER BY id",
                wardrobe);
        writer.rows(
                "packingItems",
                "SELECT to_jsonb(i) FROM packing_item i WHERE wardrobe_id = ? ORDER BY packing_list_id, garment_id",
                wardrobe);
    }
}
