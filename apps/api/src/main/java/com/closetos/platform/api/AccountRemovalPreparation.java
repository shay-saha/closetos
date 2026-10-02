package com.closetos.platform.api;

import java.util.UUID;

public interface AccountRemovalPreparation {
    void prepare(UUID request, UUID owner);
}
