package com.closetos.insights.application;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.insights.api.PairingContext;
import com.closetos.insights.api.RecommendationInsights.Pairing;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PairingRanker {
    public Optional<RankedPairing> rank(
            GarmentDetails source,
            GarmentDetails candidate,
            PairingContext context,
            Double affinity,
            int wornTogether,
            int savedTogether) {
        double slot = GarmentStructure.complementarySlot(source, candidate);
        if (source.id().equals(candidate.id())
                || slot == 0
                || !context.matches(source)
                || !context.matches(candidate)
                || source.status() != com.closetos.garment.api.GarmentStatus.AVAILABLE
                || candidate.status() != com.closetos.garment.api.GarmentStatus.AVAILABLE
                || source.processingStatus() != com.closetos.media.api.ProcessingStatus.READY
                || candidate.processingStatus() != com.closetos.media.api.ProcessingStatus.READY)
            return Optional.empty();
        var sourceStyles = styles(source);
        var candidateStyles = styles(candidate);
        var shared = new HashSet<>(sourceStyles);
        shared.retainAll(candidateStyles);
        var combined = new HashSet<>(sourceStyles);
        combined.addAll(candidateStyles);
        double style = combined.isEmpty() ? 0 : shared.size() / (double) combined.size();
        var colour = ColourCompatibility.pairing(source, candidate);
        boolean semantic =
                affinity != null && Double.isFinite(affinity) && affinity >= 0 && affinity <= 1;
        double score =
                .3 * slot
                        + .15 * style
                        + .15 * colour.map(ColourCompatibility.Evidence::score).orElse(0.0)
                        + .2 * Math.min(Math.max(wornTogether, 0) / 5.0, 1)
                        + .1 * Math.min(Math.max(savedTogether, 0) / 3.0, 1)
                        + .1 * (semantic ? affinity : 0);
        var reasons = new ArrayList<String>();
        reasons.add("Adds a complementary garment slot.");
        if (!shared.isEmpty())
            reasons.add(
                    "Shares recorded style tags: "
                            + String.join(", ", shared.stream().sorted().toList())
                            + ".");
        if (sourceStyles.isEmpty() || candidateStyles.isEmpty())
            reasons.add("Style compatibility is incomplete because style tags are missing.");
        colour.ifPresentOrElse(
                evidence -> reasons.add(evidence.reason()),
                () -> reasons.add("Colour compatibility is unknown from the recorded details."));
        if (wornTogether > 0)
            reasons.add(
                    "Recorded together in "
                            + wornTogether
                            + (wornTogether == 1 ? " wear entry." : " wear entries."));
        if (savedTogether > 0)
            reasons.add(
                    "Paired in "
                            + savedTogether
                            + (savedTogether == 1 ? " saved outfit." : " saved outfits."));
        if (semantic && affinity >= .5)
            reasons.add("Related features in the current garment embeddings.");
        if (!semantic)
            reasons.add(
                    "Semantic affinity is unavailable; this suggestion uses recorded metadata and history.");
        if (context.season() != null) reasons.add("Both pieces match the selected season tags.");
        if (context.formality() != null) reasons.add("Both pieces have the requested formality.");
        if (context.weather() != null)
            reasons.add("Both pieces have explicit tags for the requested weather assumption.");
        return Optional.of(
                new RankedPairing(
                        new Pairing(
                                candidate,
                                Math.max(0, wornTogether),
                                Math.max(0, savedTogether),
                                semantic,
                                List.copyOf(reasons)),
                        score));
    }

    private java.util.Set<String> styles(GarmentDetails garment) {
        return garment.metadata().styleTags().stream()
                .map(GarmentStructure::normalized)
                .filter(tag -> !tag.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    public record RankedPairing(Pairing pairing, double score) {}
}
