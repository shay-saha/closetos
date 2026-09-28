package com.closetos.wear.infrastructure;

import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class WearPersonalData implements PersonalDataContributor {
    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "wearEvents",
                "SELECT to_jsonb(e) - 'request_fingerprint' - 'idempotency_key' FROM wear_event e WHERE user_id = ? ORDER BY id",
                owner);
        writer.rows(
                "wearEventGarments",
                "SELECT to_jsonb(i) FROM wear_event_garment i JOIN wear_event e ON e.id = i.wear_event_id WHERE e.user_id = ? ORDER BY i.wear_event_id, i.garment_id",
                owner);
    }
}
