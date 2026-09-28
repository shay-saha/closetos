import logging
import os
import secrets
import threading
from contextlib import asynccontextmanager
from typing import Annotated

from botocore.exceptions import BotoCoreError, ClientError
from fastapi import FastAPI, Header, HTTPException

from closetos_media.analysis import BedrockAnalysis
from closetos_media.embeddings import (
    EmbeddingModelChanged,
    EmbeddingRequest,
    EmbeddingResult,
    EmbeddingService,
    EmbeddingUnavailable,
    configured_embedder,
)
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
    storage = S3Storage()
    app.state.pipeline = Pipeline(storage, BiRefNetSegmentation(), BedrockAnalysis())
    app.state.embeddings = EmbeddingService(configured_embedder(), storage)
    app.state.embedding_capacity = threading.BoundedSemaphore(2)
    app.state.capacity = threading.BoundedSemaphore(1)
    yield


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/health")
def health():
    return {"status": "ok"}


def authenticate(authorization: str | None):
    expected = "Bearer " + app.state.token
    if not authorization or not secrets.compare_digest(authorization, expected):
        raise HTTPException(401, "Authentication required")


@app.get("/embedding-model")
def embedding_model(authorization: Annotated[str | None, Header()] = None):
    authenticate(authorization)
    try:
        model = app.state.embeddings.model()
        return {"modelKey": model.key, "model": model.model_dump(by_alias=True)}
    except EmbeddingUnavailable as error:
        raise HTTPException(503, "Embeddings are not configured") from error


@app.post("/embed", response_model=EmbeddingResult)
def embed(request: EmbeddingRequest, authorization: Annotated[str | None, Header()] = None):
    authenticate(authorization)
    if not app.state.embedding_capacity.acquire(blocking=False):
        raise HTTPException(503, "The embedding worker is busy", headers={"Retry-After": "1"})
    try:
        return app.state.embeddings.embed(request)
    except EmbeddingUnavailable as error:
        raise HTTPException(503, "Embeddings are not available") from error
    except EmbeddingModelChanged as error:
        raise HTTPException(409, "The embedding model changed") from error
    except ValueError as error:
        raise HTTPException(422, "Check the search text or photograph") from error
    except (ClientError, BotoCoreError) as error:
        raise HTTPException(503, "The embedding provider is unavailable") from error
    finally:
        app.state.embedding_capacity.release()


@app.post("/process", response_model=ProcessingResult)
def process(job: WorkflowJob, authorization: Annotated[str | None, Header()] = None):
    authenticate(authorization)
    if not app.state.capacity.acquire(blocking=False):
        raise HTTPException(503, "The worker is busy", headers={"Retry-After": "5"})
    try:
        return app.state.pipeline.process(job)
    except ValueError as error:
        LOG.info("Invalid photograph for job %s (%s)", job.job_id, type(error).__name__)
        raise HTTPException(422, "The photograph could not be processed") from error
    finally:
        app.state.capacity.release()
