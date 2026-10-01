package com.closetos.media.api;

import java.util.UUID;

public interface ProcessingWorkersPort {
    boolean cancelOwner(UUID owner);
}
