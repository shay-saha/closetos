import logging

from closetos_media.analysis import AnalysisPort
from closetos_media.imaging import transform
from closetos_media.models import ProcessingResult, WorkflowJob
from closetos_media.segmentation import GarmentSegmentationPort
from closetos_media.storage import StoragePort

LOG = logging.getLogger(__name__)


class Pipeline:
    def __init__(
        self, storage: StoragePort, segmentation: GarmentSegmentationPort, analysis: AnalysisPort
    ):
        self.storage = storage
        self.segmentation = segmentation
        self.analysis = analysis

    def transform(self, job: WorkflowJob) -> ProcessingResult:
        result_key = job.output_prefix + "media.json"
        existing = self.storage.optional_json(result_key)
        if existing:
            return self.validated_result(job, existing)
        result = transform(job, self.storage, self.segmentation)
        self.storage.write(
            result_key, result.model_dump_json(by_alias=True).encode(), "application/json"
        )
        return result

    def enrich(self, job: WorkflowJob) -> ProcessingResult:
        key = job.output_prefix + "manifest.json"
        existing = self.storage.optional_json(key)
        if existing:
            return self.validated_result(job, existing)
        media = self.storage.optional_json(job.output_prefix + "media.json")
        if not media:
            raise ValueError("Verified media outputs are missing")
        result = self.validated_result(job, media)
        try:
            suggestions = self.analysis.analyse(job, result, self.storage)
            if suggestions:
                result.analysis_key = job.output_prefix + "analysis.json"
                self.storage.write(
                    result.analysis_key,
                    suggestions.model_dump_json(by_alias=True).encode(),
                    "application/json",
                )
            else:
                result.analysis_failure = "ANALYSIS_NOT_CONFIGURED"
        except Exception:
            # Analysis is optional; a successful isolation must remain available for manual review.
            LOG.exception("Analysis failed for job %s", job.job_id)
            result.analysis_key = None
            result.analysis_failure = "ANALYSIS_UNAVAILABLE"
        self.storage.write(key, result.model_dump_json(by_alias=True).encode(), "application/json")
        return result

    def process(self, job: WorkflowJob) -> ProcessingResult:
        existing = self.storage.optional_json(job.output_prefix + "manifest.json")
        if existing:
            return self.validated_result(job, existing)
        self.transform(job)
        return self.enrich(job)

    def validated_result(self, job: WorkflowJob, data: dict) -> ProcessingResult:
        result = ProcessingResult.model_validate(data)
        if (
            result.job_id != job.job_id
            or result.image_id != job.image_id
            or result.pipeline_version != job.pipeline_version
            or result.source_checksum_sha256 != job.checksum_sha256
        ):
            raise ValueError("Stored processing output belongs to a different job")
        return result
