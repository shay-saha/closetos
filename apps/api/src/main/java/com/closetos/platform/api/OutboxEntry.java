package com.closetos.platform.api;

import java.util.UUID;

public record OutboxEntry(
        UUID id, UUID aggregateId, String eventType, String payload, int publishAttempts) {}
