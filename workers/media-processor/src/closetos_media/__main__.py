import argparse
import logging
import os

from closetos_media.analysis import BedrockAnalysis
from closetos_media.models import WorkflowJob
from closetos_media.pipeline import Pipeline
from closetos_media.segmentation import BiRefNetSegmentation, download_model
from closetos_media.storage import S3Storage


def main():
    logging.basicConfig(level=logging.INFO)
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["transform", "enrich", "process", "download-model"])
    args = parser.parse_args()
    if args.stage == "download-model":
        download_model()
        return
    job = WorkflowJob.model_validate_json(os.environ["WORKFLOW_INPUT"])
    pipeline = Pipeline(S3Storage(), BiRefNetSegmentation(), BedrockAnalysis())
    getattr(pipeline, args.stage)(job)
    logging.info("Completed %s for job %s", args.stage, job.job_id)


if __name__ == "__main__":
    main()
