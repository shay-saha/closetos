package com.closetos.outfit.api;

import java.util.List;

public record OutfitPage(List<OutfitDetails> items, String nextCursor) {}
