package com.closetos.platform.api;

import java.util.UUID;

public interface OutboxAccess {
    void enqueue(
            String aggregateType, UUID aggregateId, String eventType, String key, Object payload);
}
