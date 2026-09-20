package com.closetos.activity.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.intelligence.api.AnalysisDocument;
import com.closetos.intelligence.api.SuggestionAccess;
import com.closetos.media.api.ImageRecord;
import com.closetos.media.api.ProcessingAccess;
import com.closetos.media.api.ProcessingResult;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.media.api.WorkflowJob;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessingTransitions {
    private final ProcessingAccess processing;
    private final GarmentAccess garments;
    private final SuggestionAccess suggestions;

    public ProcessingTransitions(
            ProcessingAccess processing, GarmentAccess garments, SuggestionAccess suggestions) {
        this.processing = processing;
        this.garments = garments;
        this.suggestions = suggestions;
    }

    @Transactional
    public void uploaded(ImageRecord image, String eventId) {
        processing
                .uploaded(image, eventId)
                .ifPresent(
                        job ->
                                garments.processingState(
                                        job.garmentId(),
                                        job.wardrobeId(),
                                        ProcessingStatus.UPLOADED));
    }

    @Transactional
    public WorkflowJob retry(UUID image, UUID wardrobe) {
        WorkflowJob job = processing.retry(image, wardrobe);
        garments.processingState(job.garmentId(), wardrobe, ProcessingStatus.UPLOADED);
        return job;
    }

    @Transactional
    public boolean started(WorkflowJob job, String executionArn) {
        if (!processing.started(job, executionArn)) return false;
        garments.processingState(
                job.garmentId(), job.wardrobeId(), ProcessingStatus.PROCESSING_MEDIA);
        return true;
    }

    @Transactional
    public void completed(ProcessingResult result, String eventId, AnalysisDocument analysis) {
        var job = processing.context(result.jobId());
        if (job.isPresent() && processing.complete(result, eventId)) {
            if (analysis != null)
                suggestions.record(
                        job.get().jobId(), job.get().garmentId(), job.get().wardrobeId(), analysis);
            garments.processingState(
                    job.get().garmentId(),
                    job.get().wardrobeId(),
                    ProcessingStatus.READY_FOR_REVIEW);
        }
    }

    @Transactional
    public void failed(UUID jobId, String code, String detail) {
        var job = processing.context(jobId);
        if (job.isEmpty()) return;
        processing.fail(jobId, code, detail);
        var state = processing.snapshot(job.get().imageId(), job.get().wardrobeId());
        if (state.state() == ProcessingStatus.FAILED)
            garments.processingState(
                    job.get().garmentId(), job.get().wardrobeId(), ProcessingStatus.FAILED);
    }
}
