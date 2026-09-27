package com.closetos.platform.api;

import java.util.UUID;

public interface ExpensiveActionLimits {
    void consume(UUID wardrobe, ExpensiveAction action);
}
