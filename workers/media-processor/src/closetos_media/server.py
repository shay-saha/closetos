import logging
import os
import secrets
import threading
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import FastAPI, Header, HTTPException

from closetos_media.analysis import BedrockAnalysis
from closetos_media.models import ProcessingResult, WorkflowJob
from closetos_media.pipeline import Pipeline
from closetos_media.segmentation import BiRefNetSegmentation
from closetos_media.storage import S3Storage

LOG = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.token = os.environ["MEDIA_WORKER_TOKEN"]
    if len(app.state.token) < 32:
        raise ValueError("The worker token must be at least 32 characters")
    app.state.pipeline = Pipeline(S3Storage(), BiRefNetSegmentation(), BedrockAnalysis())
    app.state.capacity = threading.BoundedSemaphore(1)
    yield


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/process", response_model=ProcessingResult)
def process(job: WorkflowJob, authorization: Annotated[str | None, Header()] = None):
    expected = "Bearer " + app.state.token
    if not authorization or not secrets.compare_digest(authorization, expected):
        raise HTTPException(401, "Authentication required")
    if not app.state.capacity.acquire(blocking=False):
        raise HTTPException(503, "The worker is busy", headers={"Retry-After": "5"})
    try:
        return app.state.pipeline.process(job)
    except ValueError as error:
        LOG.info("Invalid photograph for job %s: %s", job.job_id, error)
        raise HTTPException(422, "The photograph could not be processed") from error
    finally:
        app.state.capacity.release()
