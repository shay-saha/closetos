package com.closetos.wardrobe.infrastructure;

import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class WardrobePersonalData implements PersonalDataContributor {
    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "wardrobes",
                "SELECT to_jsonb(w) FROM wardrobe w WHERE owner_id = ? ORDER BY id",
                owner);
    }
}
