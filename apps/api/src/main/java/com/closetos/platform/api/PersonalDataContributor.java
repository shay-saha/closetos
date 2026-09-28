package com.closetos.platform.api;

import java.time.Instant;
import java.util.UUID;

public interface PersonalDataContributor {
    void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry);
}
