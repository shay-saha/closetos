package com.closetos.insights.application;

import com.closetos.garment.api.GarmentDetails;
import java.util.Optional;
import java.util.Set;

final class ColourCompatibility {
    private static final Set<String> NEUTRALS =
            Set.of("black", "white", "grey", "gray", "cream", "beige", "brown", "navy");

    private ColourCompatibility() {}

    static boolean known(GarmentDetails garment) {
        return rgb(garment.metadata().primaryColourHex()) != null || !name(garment).isEmpty();
    }

    static Optional<Evidence> duplicate(GarmentDetails first, GarmentDetails second) {
        var a = rgb(first.metadata().primaryColourHex());
        var b = rgb(second.metadata().primaryColourHex());
        if (a != null && b != null) {
            double distance =
                    Math.sqrt(
                            (square(a[0] - b[0]) + square(a[1] - b[1]) + square(a[2] - b[2])) / 3);
            return distance <= .2
                    ? Optional.of(new Evidence(1 - distance, "Recorded colours are close."))
                    : Optional.empty();
        }
        var colour = name(first);
        return !colour.isEmpty() && colour.equals(name(second))
                ? Optional.of(new Evidence(1, "The pieces share the same recorded colour label."))
                : Optional.empty();
    }

    static Optional<Evidence> pairing(GarmentDetails first, GarmentDetails second) {
        var a = rgb(first.metadata().primaryColourHex());
        var b = rgb(second.metadata().primaryColourHex());
        if (a != null && b != null) {
            var ah = hsv(a);
            var bh = hsv(b);
            if (ah[1] < .15 || bh[1] < .15 || ah[2] < .18 || bh[2] < .18)
                return Optional.of(
                        new Evidence(.8, "A neutral recorded colour can balance the pairing."));
            double hue = Math.abs(ah[0] - bh[0]);
            hue = Math.min(hue, 360 - hue);
            if (hue <= 30)
                return Optional.of(new Evidence(.7, "The recorded colours share a hue."));
            if (hue <= 60)
                return Optional.of(
                        new Evidence(.8, "The recorded colours have neighbouring hues."));
            if (hue >= 150)
                return Optional.of(
                        new Evidence(.75, "The recorded colours have complementary hues."));
            return Optional.of(new Evidence(.3, "The recorded colours offer a stronger contrast."));
        }
        if (name(first).isEmpty() || name(second).isEmpty()) return Optional.empty();
        if (NEUTRALS.contains(name(first)) || NEUTRALS.contains(name(second)))
            return Optional.of(
                    new Evidence(.7, "A neutral recorded colour can balance the pairing."));
        return !name(first).isEmpty() && name(first).equals(name(second))
                ? Optional.of(new Evidence(.6, "The pieces share the same recorded colour label."))
                : Optional.empty();
    }

    private static String name(GarmentDetails garment) {
        return GarmentStructure.normalized(garment.metadata().primaryColourName())
                .replace("gray", "grey");
    }

    private static double[] rgb(String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) return null;
        return new double[] {
            Integer.parseInt(hex.substring(1, 3), 16) / 255.0,
            Integer.parseInt(hex.substring(3, 5), 16) / 255.0,
            Integer.parseInt(hex.substring(5, 7), 16) / 255.0
        };
    }

    private static double[] hsv(double[] rgb) {
        double max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
        double min = Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
        double delta = max - min;
        double hue =
                delta == 0
                        ? 0
                        : max == rgb[0]
                                ? (rgb[1] - rgb[2]) / delta
                                : max == rgb[1]
                                        ? (rgb[2] - rgb[0]) / delta + 2
                                        : (rgb[0] - rgb[1]) / delta + 4;
        return new double[] {(hue * 60 + 360) % 360, max == 0 ? 0 : delta / max, max};
    }

    private static double square(double value) {
        return value * value;
    }

    record Evidence(double score, String reason) {}
}
