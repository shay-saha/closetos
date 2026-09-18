package com.closetos.wardrobe.api;

import java.time.Instant;
import java.util.UUID;

public record WardrobeDetails(UUID id, String name, long version, Instant createdAt) {}
