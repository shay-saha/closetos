import base64
import io
import json
import os
from pathlib import Path

import boto3
import numpy as np
import pytest
from botocore.response import StreamingBody
from botocore.stub import Stubber
from fastapi.testclient import TestClient
from PIL import Image
from pydantic import ValidationError
from test_pipeline import MemoryStorage, job_for, photograph

from closetos_media.embeddings import (
    BedrockEmbeddings,
    ClipEmbeddings,
    EmbeddingModel,
    EmbeddingRequest,
    EmbeddingService,
    EmbeddingUnavailable,
    clip_home,
    configured_embedder,
    normalized,
    prepared_image,
)
from closetos_media.storage import checksum


def response_body(value):
    encoded = json.dumps(value).encode()
    return {
        "body": StreamingBody(io.BytesIO(encoded), len(encoded)),
        "contentType": "application/json",
    }


def bedrock(monkeypatch, dimensions=256):
    client = boto3.client(
        "bedrock-runtime",
        region_name="eu-west-2",
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )
    monkeypatch.setattr("closetos_media.embeddings.boto3.client", lambda *args, **kwargs: client)
    provider = BedrockEmbeddings("amazon.titan-embed-image-v1", dimensions, "1")
    return provider, client


def test_titan_request_and_model_identity_are_versioned_and_vectors_are_normalized(monkeypatch):
    provider, client = bedrock(monkeypatch)
    text = "a black formal dress"
    with Stubber(client) as stub:
        stub.add_response(
            "invoke_model",
            response_body({"embedding": [3, 4] + [0] * 254, "inputTextTokenCount": 5}),
            {
                "modelId": "amazon.titan-embed-image-v1",
                "contentType": "application/json",
                "accept": "application/json",
                "body": json.dumps(
                    {"embeddingConfig": {"outputEmbeddingLength": 256}, "inputText": text},
                    separators=(",", ":"),
                ),
            },
        )
        service = EmbeddingService(provider, MemoryStorage())
        result = service.embed(EmbeddingRequest(text=text))
        stub.assert_no_pending_responses()
    assert result.vector[:2] == [0.6, 0.8]
    assert len(result.vector) == 256
    assert result.model_key == provider.model.key
    assert result.model.model_version == "1"
    assert result.model.pipeline_version == "multimodal-1"
    assert (
        EmbeddingModel(**{**provider.model.model_dump(), "model_version": "2"}).key
        != result.model_key
    )
    assert (
        EmbeddingModel(**{**provider.model.model_dump(), "dimensions": 1024}).key
        != result.model_key
    )


def test_titan_combines_image_and_text_without_exposing_storage_keys(monkeypatch):
    provider, client = bedrock(monkeypatch)
    source = photograph()
    captured = []
    client.meta.events.register(
        "before-parameter-build.bedrock-runtime.InvokeModel",
        lambda params, **kwargs: captured.append(params),
    )
    with Stubber(client) as stub:
        stub.add_response("invoke_model", response_body({"embedding": [1] + [0] * 255}))
        result = EmbeddingService(provider, MemoryStorage()).embed(
            EmbeddingRequest(text="a shirt", image_base64=base64.b64encode(source).decode())
        )
    body = json.loads(captured[0]["body"])
    assert set(body) == {"embeddingConfig", "inputText", "inputImage"}
    assert body["inputText"] == "a shirt"
    with Image.open(io.BytesIO(base64.b64decode(body["inputImage"]))) as image:
        assert image.format == "JPEG"
        assert image.mode == "RGB"
        assert max(image.size) <= 1600
    assert len(result.vector) == 256


@pytest.mark.parametrize(
    "result",
    [
        {"message": "failed", "embedding": [1] * 256},
        {},
        {"embedding": [1] * 255},
        {"embedding": [0] * 256},
        {"embedding": [True] * 256},
        {"embedding": ["1"] * 256},
        {"embedding": [float("nan")] * 256},
        {"embedding": [float("inf")] * 256},
    ],
)
def test_invalid_titan_results_never_become_embeddings(monkeypatch, result):
    provider, client = bedrock(monkeypatch)
    with Stubber(client) as stub:
        stub.add_response("invoke_model", response_body(result))
        with pytest.raises(ValueError):
            provider.embed("a shirt", None)


class RecordingEmbeddings:
    model = EmbeddingModel(
        provider="local-clip", model_id="fixture", model_version="1", dimensions=512
    )

    def __init__(self):
        self.calls = []

    def embed(self, text, image):
        self.calls.append((text, image))
        return [1.0] + [0.0] * 511


def test_stored_derivative_is_checksum_verified_and_transparency_is_composited():
    source = photograph()
    job = job_for(source)
    key = job.output_prefix + "display.webp"
    storage = MemoryStorage()
    storage.objects[key] = source
    provider = RecordingEmbeddings()
    service = EmbeddingService(provider, storage)
    service.embed(
        EmbeddingRequest(text="a shirt", image_key=key, image_checksum_sha256=checksum(source))
    )
    assert len(provider.calls) == 1
    assert provider.calls[0][1].mode == "RGB"
    with pytest.raises(ValueError, match="checksum"):
        service.embed(EmbeddingRequest(image_key=key, image_checksum_sha256=checksum(b"other")))
    assert len(provider.calls) == 1
    transparent = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    buffer = io.BytesIO()
    transparent.save(buffer, format="PNG")
    assert prepared_image(buffer.getvalue()).getpixel((0, 0)) == (255, 255, 255)


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"text": " "},
        {"text": "x" * 1001},
        {"text": "shirt", "arbitrary": "field"},
        {"imageKey": "../../secret"},
        {"imageBase64": "x", "imageChecksumSha256": "x" * 44},
    ],
)
def test_embedding_requests_are_bounded_and_cannot_select_arbitrary_objects(payload):
    with pytest.raises(ValidationError):
        EmbeddingRequest.model_validate(payload)


def test_corrupt_animated_or_oversized_images_never_reach_inference():
    provider = RecordingEmbeddings()
    service = EmbeddingService(provider, MemoryStorage())
    for source in [b"not an image", b"x" * (8 * 1024 * 1024 + 1)]:
        with pytest.raises(ValueError):
            service.embed(EmbeddingRequest(image_base64=base64.b64encode(source).decode()))
    animated = io.BytesIO()
    Image.new("RGB", (64, 64), "red").save(
        animated, format="WEBP", save_all=True, append_images=[Image.new("RGB", (64, 64), "blue")]
    )
    with pytest.raises(ValueError):
        prepared_image(animated.getvalue())
    assert provider.calls == []


def test_no_model_configuration_does_not_invent_vectors_or_initialize_aws(monkeypatch, tmp_path):
    monkeypatch.delenv("EMBEDDING_PROVIDER", raising=False)
    monkeypatch.delenv("BEDROCK_EMBEDDING_MODEL_ID", raising=False)
    monkeypatch.setattr(
        "closetos_media.embeddings.boto3.client",
        lambda *args, **kwargs: pytest.fail("AWS must not be initialized"),
    )
    assert configured_embedder() is None
    with pytest.raises(EmbeddingUnavailable):
        EmbeddingService(None, MemoryStorage()).embed(EmbeddingRequest(text="shirt"))
    with pytest.raises(EmbeddingUnavailable):
        ClipEmbeddings(tmp_path).embed("shirt", None)
    with pytest.raises(ValueError):
        normalized([[1, 2]], 1)


def test_embedding_endpoints_require_authentication_and_release_capacity_after_bad_input(
    monkeypatch,
):
    from closetos_media.server import app

    monkeypatch.setenv("MEDIA_WORKER_TOKEN", "t" * 32)
    monkeypatch.setattr("closetos_media.server.S3Storage", MemoryStorage)
    monkeypatch.setattr("closetos_media.server.configured_embedder", RecordingEmbeddings)
    headers = {"Authorization": "Bearer " + "t" * 32}
    with TestClient(app) as client:
        assert client.get("/embedding-model").status_code == 401
        assert client.post("/embed", json={"text": "shirt"}).status_code == 401
        assert client.get("/embedding-model", headers=headers).status_code == 200
        assert (
            client.post("/embed", headers=headers, json={"imageBase64": "!!!"}).status_code == 422
        )
        valid = client.post("/embed", headers=headers, json={"text": "shirt"})
        assert valid.status_code == 200
        assert len(valid.json()["vector"]) == 512
        assert (
            client.post(
                "/embed", headers=headers, json={"text": "shirt", "expectedModelKey": "0" * 64}
            ).status_code
            == 409
        )
        assert app.state.embedding_capacity.acquire(blocking=False)
        assert app.state.embedding_capacity.acquire(blocking=False)
        assert client.post("/embed", headers=headers, json={"text": "shirt"}).status_code == 503
        app.state.embedding_capacity.release()
        app.state.embedding_capacity.release()
        app.state.embeddings.provider = None
        assert client.get("/embedding-model", headers=headers).status_code == 503
        assert client.post("/embed", headers=headers, json={"text": "shirt"}).status_code == 503


@pytest.mark.skipif(
    os.getenv("EMBEDDING_EVALUATION") != "1",
    reason="Real model evaluation runs in the worker quality gate",
)
def test_real_clip_retrieves_related_clothing_in_a_shared_image_and_text_space():
    model = ClipEmbeddings(clip_home())
    vectors = {
        label: np.asarray(model.embed(text, None))
        for label, text in {
            "shirt": "a green short sleeved t-shirt",
            "jumper": "a green cotton top with short sleeves",
            "shoes": "a pair of red high heeled shoes",
            "trousers": "blue denim jeans",
            "unrelated": "a golden retriever playing in a park",
        }.items()
    }
    fixture = Path(__file__).resolve().parents[3] / "e2e/fixtures/shirt.png"
    image = np.asarray(model.embed(None, prepared_image(fixture.read_bytes())))
    ranking = sorted(vectors, key=lambda label: float(image @ vectors[label]), reverse=True)
    assert ranking[0] == "shirt", ranking
    assert set(ranking[:2]) == {"shirt", "jumper"}, ranking
    assert float(vectors["shirt"] @ vectors["jumper"]) > float(vectors["shirt"] @ vectors["shoes"])
    assert float(vectors["shirt"] @ vectors["jumper"]) > float(
        vectors["shirt"] @ vectors["unrelated"]
    )
    combined = np.asarray(model.embed("a green t-shirt", prepared_image(fixture.read_bytes())))
    assert float(combined @ vectors["shirt"]) > float(combined @ vectors["trousers"])
    assert np.linalg.norm(combined) == pytest.approx(1.0)
