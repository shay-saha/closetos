package com.closetos.platform.api;

import java.util.UUID;
import java.util.function.Supplier;

public interface MediaSigningAdmission {
    <T> T sign(UUID owner, Supplier<T> operation);
}
