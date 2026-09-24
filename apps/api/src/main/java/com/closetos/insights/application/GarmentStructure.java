package com.closetos.insights.application;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentDetails;
import java.util.Locale;
import java.util.Set;

final class GarmentStructure {
    private static final Set<String> SHARED_LAYERS =
            Set.of("cardigan", "jacket", "blazer", "overshirt", "shirt-jacket");

    private GarmentStructure() {}

    static boolean duplicateCategory(GarmentDetails first, GarmentDetails second) {
        if (first.metadata().category() == second.metadata().category()) return true;
        var categories = Set.of(first.metadata().category(), second.metadata().category());
        var subtype = normalized(first.metadata().subcategory());
        return categories.equals(Set.of(GarmentCategory.TOP, GarmentCategory.OUTERWEAR))
                && SHARED_LAYERS.contains(subtype)
                && subtype.equals(normalized(second.metadata().subcategory()));
    }

    static String sharedLayer(GarmentDetails garment) {
        var category = garment.metadata().category();
        var subtype = normalized(garment.metadata().subcategory());
        return (category == GarmentCategory.TOP || category == GarmentCategory.OUTERWEAR)
                        && SHARED_LAYERS.contains(subtype)
                ? subtype
                : "";
    }

    static double complementarySlot(GarmentDetails first, GarmentDetails second) {
        var a = slotCategory(first);
        var b = slotCategory(second);
        if (a == GarmentCategory.OTHER || b == GarmentCategory.OTHER) return 0;
        if (a == b) {
            if (a != GarmentCategory.ACCESSORY && a != GarmentCategory.JEWELLERY) return 0;
            var firstSlot = accessorySlot(first);
            var secondSlot = accessorySlot(second);
            return !firstSlot.isEmpty() && !secondSlot.isEmpty() && !firstSlot.equals(secondSlot)
                    ? .45
                    : 0;
        }
        var pair = Set.of(a, b);
        if (pair.contains(GarmentCategory.DRESS)
                && (pair.contains(GarmentCategory.TOP) || pair.contains(GarmentCategory.BOTTOM)))
            return 0;
        if (pair.equals(Set.of(GarmentCategory.TOP, GarmentCategory.BOTTOM))) return 1;
        if (pair.contains(GarmentCategory.SHOES)) return .9;
        if (pair.contains(GarmentCategory.OUTERWEAR)) return .8;
        if (pair.contains(GarmentCategory.BAG)) return .6;
        return .5;
    }

    private static GarmentCategory slotCategory(GarmentDetails garment) {
        return garment.metadata().category() == GarmentCategory.TOP
                        && !sharedLayer(garment).isEmpty()
                ? GarmentCategory.OUTERWEAR
                : garment.metadata().category();
    }

    private static String accessorySlot(GarmentDetails garment) {
        return switch (normalized(garment.metadata().subcategory())) {
            case "scarf", "scarves", "necklace", "necklaces", "pendant" -> "NECK";
            case "belt", "belts" -> "WAIST";
            case "hat", "hats", "cap", "beanie" -> "HEAD";
            case "gloves", "glove" -> "HANDS";
            case "earring", "earrings" -> "EARS";
            case "bracelet", "bracelets", "watch", "watches" -> "WRIST";
            case "ring", "rings" -> "FINGER";
            case "sunglasses", "glasses" -> "EYES";
            default -> "";
        };
    }

    static String normalized(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }
}
