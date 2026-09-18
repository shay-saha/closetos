package com.closetos.garment.api;

import java.util.List;

public record GarmentPage(List<GarmentDetails> items, String nextCursor) {
    public GarmentPage {
        items = List.copyOf(items);
    }
}
