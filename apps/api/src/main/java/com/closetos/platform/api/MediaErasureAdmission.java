package com.closetos.platform.api;

import java.util.UUID;

public interface MediaErasureAdmission {
    boolean allowed(UUID request, UUID owner, UUID lease);
}
