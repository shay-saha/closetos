package com.closetos.identity.api;

import java.time.Instant;
import java.util.UUID;

public interface AccountRemovalAccess {
    RemovalRequest request();

    record RemovalRequest(UUID requestId, Instant requestedAt) {}
}
