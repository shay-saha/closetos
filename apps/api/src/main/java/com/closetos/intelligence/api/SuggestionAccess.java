package com.closetos.intelligence.api;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public interface SuggestionAccess {
    AnalysisDocument validate(UUID imageId, String pipelineVersion, String document);

    void record(UUID jobId, UUID garmentId, UUID wardrobeId, AnalysisDocument document);

    List<SuggestionDetails> list(UUID garmentId, UUID wardrobeId);

    SuggestionDetails pending(UUID suggestionId, UUID garmentId, UUID wardrobeId, long version);

    void accepted(UUID suggestionId, UUID wardrobeId, ObjectNode metadata);

    void rejected(UUID suggestionId, UUID wardrobeId);

    ObjectNode canonicalValues(SuggestionDetails suggestion);
}
