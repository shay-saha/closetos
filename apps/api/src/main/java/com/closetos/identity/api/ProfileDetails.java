package com.closetos.identity.api;

import java.util.UUID;

public record ProfileDetails(
        UUID id,
        String displayName,
        String locale,
        String currency,
        String timezone,
        boolean deleteOriginalAfterIsolation,
        long version) {}
