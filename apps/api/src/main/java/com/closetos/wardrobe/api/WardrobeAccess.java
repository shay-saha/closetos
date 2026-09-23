package com.closetos.wardrobe.api;

import java.util.UUID;

public interface WardrobeAccess {
    UUID currentWardrobeId();

    boolean exists(UUID wardrobe);
}
