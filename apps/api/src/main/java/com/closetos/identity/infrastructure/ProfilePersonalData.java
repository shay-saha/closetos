package com.closetos.identity.infrastructure;

import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ProfilePersonalData implements PersonalDataContributor {
    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "profile",
                "SELECT to_jsonb(p) - 'cognito_sub' FROM user_profile p WHERE id = ?",
                owner);
    }
}
