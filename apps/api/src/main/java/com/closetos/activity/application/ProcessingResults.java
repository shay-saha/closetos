package com.closetos.activity.application;

import com.closetos.media.api.AssetDescriptor;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.ProcessingAccess;
import com.closetos.media.api.ProcessingResult;
import com.closetos.media.api.WorkflowJob;
import com.closetos.platform.api.DomainException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProcessingResults {
    private static final Set<String> DERIVATIVES =
            Set.of("isolated", "display", "card", "thumbnail", "mask");
    private final ProcessingAccess processing;
    private final ProcessingTransitions transitions;
    private final ObjectStoragePort storage;
    private final JsonMapper json;

    public ProcessingResults(
            ProcessingAccess processing,
            ProcessingTransitions transitions,
            ObjectStoragePort storage,
            JsonMapper json) {
        this.processing = processing;
        this.transitions = transitions;
        this.storage = storage;
        this.json = json;
    }

    public void manifest(WorkflowJob job, String eventId) {
        accept(
                json.readValue(storage.readJson(job.manifestKey()), ProcessingResult.class),
                eventId);
    }

    public void accept(ProcessingResult result, String eventId) {
        if (result == null || result.jobId() == null)
            throw DomainException.invalid("Invalid processing output.");
        WorkflowJob job = processing.context(result.jobId()).orElse(null);
        if (job == null) return;
        validate(job, result);
        transitions.completed(result, eventId);
    }

    private void validate(WorkflowJob job, ProcessingResult result) {
        if (!job.imageId().equals(result.imageId())
                || !job.pipelineVersion().equals(result.pipelineVersion())
                || !job.checksumSha256().equals(result.sourceChecksumSha256())
                || result.assets() == null
                || !result.assets().keySet().equals(DERIVATIVES)
                || !Double.isFinite(result.foregroundFraction())
                || result.foregroundFraction() <= 0
                || result.foregroundFraction() >= 1
                || result.segmentationModel() == null
                || result.segmentationModel().isBlank()) {
            throw DomainException.invalid("Processing output did not match this photograph.");
        }
        if (result.analysisKey() != null
                && !result.analysisKey().equals(job.outputPrefix() + "analysis.json")) {
            throw DomainException.invalid("Invalid analysis output.");
        }
        for (Map.Entry<String, AssetDescriptor> entry : result.assets().entrySet()) {
            AssetDescriptor asset = entry.getValue();
            String expectedKey =
                    job.outputPrefix()
                            + entry.getKey()
                            + (entry.getKey().equals("mask") ? ".png" : ".webp");
            if (asset == null
                    || asset.checksumSha256() == null
                    || !expectedKey.equals(asset.key())
                    || asset.size() <= 0
                    || asset.size() > 25 * 1024 * 1024
                    || asset.width() <= 0
                    || asset.height() <= 0
                    || asset.width() > 4096
                    || asset.height() > 4096) {
                throw DomainException.invalid("Invalid derived photograph.");
            }
            var stored =
                    storage.head(asset.key())
                            .orElseThrow(
                                    () ->
                                            DomainException.invalid(
                                                    "A derived photograph is missing."));
            if (stored.size() != asset.size()
                    || !asset.checksumSha256().equals(stored.checksumSha256())) {
                throw DomainException.invalid("A derived photograph failed verification.");
            }
        }
    }
}
