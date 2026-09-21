package com.closetos.garment.api;

public enum GarmentView {
    RECENTLY_ADDED(GarmentSort.NEWEST),
    RECENTLY_WORN(GarmentSort.RECENTLY_WORN),
    MOST_WORN(GarmentSort.MOST_WORN),
    LEAST_WORN(GarmentSort.LEAST_WORN),
    NEVER_WORN(GarmentSort.NEWEST),
    FORGOTTEN(GarmentSort.OLDEST),
    BEST_COST_PER_WEAR(GarmentSort.COST_PER_WEAR),
    HIGHEST_COST_PER_WEAR(GarmentSort.HIGH_COST_PER_WEAR),
    CURRENT_SEASON(GarmentSort.NEWEST),
    GOING_OUT(GarmentSort.NEWEST),
    WORK(GarmentSort.NEWEST),
    FORMAL(GarmentSort.NEWEST),
    PACKED(GarmentSort.NEWEST),
    LAUNDRY(GarmentSort.NEWEST),
    ARCHIVED(GarmentSort.NEWEST);

    private final GarmentSort sort;

    GarmentView(GarmentSort sort) {
        this.sort = sort;
    }

    public GarmentSort defaultSort() {
        return sort;
    }
}
