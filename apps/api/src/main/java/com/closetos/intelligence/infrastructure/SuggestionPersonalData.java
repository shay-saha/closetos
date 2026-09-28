package com.closetos.intelligence.infrastructure;

import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class SuggestionPersonalData implements PersonalDataContributor {
    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "suggestions",
                "SELECT to_jsonb(s) FROM garment_ai_suggestion s JOIN garment g ON g.id = s.garment_id WHERE g.wardrobe_id = ? ORDER BY s.id",
                wardrobe);
    }
}
