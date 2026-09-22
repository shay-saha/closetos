import base64
import hashlib
import io
import json
import math
import os
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import boto3
import numpy as np
from botocore.config import Config
from PIL import Image, ImageCms, ImageOps
from pydantic import Field, model_validator

from closetos_media.models import WireModel
from closetos_media.storage import StoragePort, checksum

PIPELINE_VERSION = "multimodal-1"
CLIP_MODEL = "Xenova/clip-vit-base-patch32"
CLIP_REVISION = "d15189d7028b43f1d3e65039190477f6af591c2a"
CLIP_FILES = {
    "text_model_quantized.onnx": "73baab855d406190da9faa498cfedf65f15cf309f4cc7385b7b032e6d08e5c3a",
    "vision_model_quantized.onnx": (
        "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299"
    ),
    "tokenizer.json": "f7f3b7af117d467b58374797691a6438d3e6b9e9cef800dfd5dced7f697a90cd",
    "preprocessor_config.json": "6f638fb9401a6d6296feff533ee7efe657b787c49f954f82f5906b36ef2a1b1f",
}
MAX_IMAGE_BYTES = 8 * 1024 * 1024


class EmbeddingUnavailable(RuntimeError):
    pass


class EmbeddingModelChanged(ValueError):
    pass


class EmbeddingRequest(WireModel):
    expected_model_key: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")
    text: str | None = Field(default=None, min_length=1, max_length=1000)
    image_key: str | None = Field(
        default=None,
        pattern=r"^users/[0-9a-f-]{36}/garments/[0-9a-f-]{36}/images/[0-9a-f-]{36}/pipelines/\d+-r[1-5]/(?:display|card)\.(?:png|webp)$",
    )
    image_checksum_sha256: str | None = Field(default=None, min_length=44, max_length=44)
    image_base64: str | None = Field(default=None, min_length=1, max_length=11_184_812)

    @model_validator(mode="after")
    def input_scope(self):
        if self.text is not None and not self.text.strip():
            raise ValueError("Embedding text cannot be blank")
        if not self.text and not self.image_key and not self.image_base64:
            raise ValueError("Text or an image is required")
        if self.image_key and self.image_base64:
            raise ValueError("Only one image input is allowed")
        if bool(self.image_key) != bool(self.image_checksum_sha256):
            raise ValueError("A stored photograph requires its checksum")
        if self.image_checksum_sha256:
            if len(base64.b64decode(self.image_checksum_sha256, validate=True)) != 32:
                raise ValueError("Invalid photograph checksum")
        return self


class EmbeddingModel(WireModel):
    provider: str
    model_id: str
    model_version: str | None
    pipeline_version: str = PIPELINE_VERSION
    dimensions: int

    @property
    def key(self) -> str:
        return hashlib.sha256(
            json.dumps(
                self.model_dump(by_alias=True), sort_keys=True, separators=(",", ":")
            ).encode()
        ).hexdigest()


class EmbeddingResult(WireModel):
    model_key: str
    model: EmbeddingModel
    vector: list[float]


class EmbeddingPort(Protocol):
    model: EmbeddingModel

    def embed(self, text: str | None, image: Image.Image | None) -> list[float]: ...


def normalized(vector, dimensions: int) -> list[float]:
    if not isinstance(vector, (list, tuple, np.ndarray)) or len(vector) != dimensions:
        raise ValueError("Embedding has the wrong dimensions")
    if any(isinstance(value, (bool, str)) for value in vector):
        raise ValueError("Embedding must contain numbers")
    values = np.asarray(vector, dtype=np.float64)
    if values.ndim != 1 or not np.isfinite(values).all():
        raise ValueError("Embedding contains invalid coordinates")
    norm = float(np.linalg.norm(values))
    if not math.isfinite(norm) or norm < 1e-12:
        raise ValueError("Embedding has no direction")
    return (values / norm).tolist()


def prepared_image(data: bytes) -> Image.Image:
    if len(data) > MAX_IMAGE_BYTES:
        raise ValueError("Photograph exceeds its size limit")
    try:
        with Image.open(io.BytesIO(data)) as opened:
            if opened.format not in {"JPEG", "PNG", "WEBP"} or getattr(opened, "n_frames", 1) != 1:
                raise ValueError("Use a single JPEG, PNG or WebP photograph")
            if opened.width * opened.height > 24_000_000 or min(opened.size) < 1:
                raise ValueError("Photograph dimensions exceed their limit")
            image = ImageOps.exif_transpose(opened).convert("RGBA")
            profile = opened.info.get("icc_profile")
            if profile:
                alpha = image.getchannel("A")
                image = ImageCms.profileToProfile(
                    image.convert("RGB"),
                    ImageCms.ImageCmsProfile(io.BytesIO(profile)),
                    ImageCms.createProfile("sRGB"),
                    outputMode="RGB",
                )
                image.putalpha(alpha)
            background = Image.new("RGB", image.size, "white")
            background.paste(image, mask=image.getchannel("A"))
            background.thumbnail((1600, 1600), Image.Resampling.LANCZOS)
            return background
    except (
        Image.DecompressionBombError,
        Image.DecompressionBombWarning,
        ImageCms.PyCMSError,
        OSError,
    ) as error:
        raise ValueError("Invalid photograph") from error


class BedrockEmbeddings:
    def __init__(self, model_id: str, dimensions: int = 1024, model_version: str | None = None):
        if dimensions not in {256, 384, 1024}:
            raise ValueError("Titan embeddings require 256, 384 or 1024 dimensions")
        self.model = EmbeddingModel(
            provider="bedrock",
            model_id=model_id,
            model_version=model_version,
            dimensions=dimensions,
        )
        self.client = boto3.client(
            "bedrock-runtime",
            region_name=os.getenv("AWS_REGION", "eu-west-2"),
            config=Config(
                connect_timeout=5,
                read_timeout=30,
                retries={"max_attempts": 1, "mode": "standard"},
            ),
        )

    def embed(self, text: str | None, image: Image.Image | None) -> list[float]:
        body = {"embeddingConfig": {"outputEmbeddingLength": self.model.dimensions}}
        if text:
            body["inputText"] = text
        if image is not None:
            buffer = io.BytesIO()
            image.save(buffer, format="JPEG", quality=90)
            body["inputImage"] = base64.b64encode(buffer.getvalue()).decode("ascii")
        response = self.client.invoke_model(
            modelId=self.model.model_id,
            contentType="application/json",
            accept="application/json",
            body=json.dumps(body, separators=(",", ":")),
        )
        with response["body"] as stream:
            encoded = stream.read(131_073)
        if len(encoded) > 131_072:
            raise ValueError("Embedding response exceeds its size limit")
        result = json.loads(encoded)
        if result.get("message"):
            raise ValueError("Bedrock did not return an embedding")
        return normalized(result.get("embedding"), self.model.dimensions)


def clip_home() -> Path:
    default = (
        Path(os.environ["U2NET_HOME"]) / "clip"
        if "U2NET_HOME" in os.environ
        else Path.home() / ".cache/closetos/clip"
    )
    return Path(os.getenv("CLIP_HOME", str(default)))


class ClipEmbeddings:
    def __init__(self, directory: Path | None = None):
        self.directory = directory or clip_home()
        self.model = EmbeddingModel(
            provider="local-clip",
            model_id=CLIP_MODEL,
            model_version=CLIP_REVISION + ":int8",
            dimensions=512,
        )
        self._models: ClipModels | None = None
        self._lock = threading.Lock()

    def models(self):
        with self._lock:
            if self._models is None:
                import onnxruntime as ort
                from tokenizers import Tokenizer

                for name, expected in CLIP_FILES.items():
                    path = self.directory / name
                    if not path.is_file():
                        raise EmbeddingUnavailable("Local embedding model is missing or unverified")
                    with path.open("rb") as source:
                        if hashlib.file_digest(source, "sha256").hexdigest() != expected:
                            raise EmbeddingUnavailable(
                                "Local embedding model is missing or unverified"
                            )
                options = ort.SessionOptions()
                options.intra_op_num_threads = 2
                options.inter_op_num_threads = 1
                text = ort.InferenceSession(
                    str(self.directory / "text_model_quantized.onnx"),
                    sess_options=options,
                    providers=["CPUExecutionProvider"],
                )
                vision = ort.InferenceSession(
                    str(self.directory / "vision_model_quantized.onnx"),
                    sess_options=options,
                    providers=["CPUExecutionProvider"],
                )
                tokenizer = Tokenizer.from_file(str(self.directory / "tokenizer.json"))
                tokenizer.enable_truncation(max_length=77)
                tokenizer.enable_padding(length=77, pad_id=49407, pad_token="<|endoftext|>")
                self._models = ClipModels(text, vision, tokenizer)
        return self._models

    def embed(self, text: str | None, image: Image.Image | None) -> list[float]:
        models = self.models()
        vectors = []
        if text:
            encoding = models.tokenizer.encode(text)
            inputs = {
                "input_ids": np.asarray([encoding.ids], dtype=np.int64),
                "attention_mask": np.asarray([encoding.attention_mask], dtype=np.int64),
            }
            output = models.text.run(
                ["text_embeds"],
                {entry.name: inputs[entry.name] for entry in models.text.get_inputs()},
            )[0][0]
            vectors.append(normalized(output, 512))
        if image is not None:
            width, height = image.size
            if width < height:
                size = (224, int(height * 224 / width))
            else:
                size = (int(width * 224 / height), 224)
            resized = image.resize(size, Image.Resampling.BICUBIC)
            left, top = (size[0] - 224) // 2, (size[1] - 224) // 2
            pixels = (
                np.asarray(resized.crop((left, top, left + 224, top + 224)), dtype=np.float32) / 255
            )
            pixels = (
                pixels - np.asarray([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
            ) / np.asarray([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
            output = models.vision.run(
                ["image_embeds"], {"pixel_values": np.transpose(pixels, (2, 0, 1))[None, ...]}
            )[0][0]
            vectors.append(normalized(output, 512))
        if not vectors:
            raise ValueError("Text or an image is required")
        return normalized(np.mean(vectors, axis=0), 512)


@dataclass
class ClipModels:
    text: object
    vision: object
    tokenizer: object


def configured_embedder() -> EmbeddingPort | None:
    provider = os.getenv("EMBEDDING_PROVIDER", "bedrock")
    if provider == "local":
        return ClipEmbeddings()
    if provider != "bedrock":
        raise ValueError("Choose local or bedrock embeddings")
    model = os.getenv("BEDROCK_EMBEDDING_MODEL_ID")
    return (
        BedrockEmbeddings(
            model,
            int(os.getenv("BEDROCK_EMBEDDING_DIMENSIONS", "1024")),
            os.getenv("BEDROCK_EMBEDDING_MODEL_VERSION"),
        )
        if model
        else None
    )


class EmbeddingService:
    def __init__(self, provider: EmbeddingPort | None, storage: StoragePort):
        self.provider = provider
        self.storage = storage

    def model(self) -> EmbeddingModel:
        if self.provider is None:
            raise EmbeddingUnavailable("Embeddings are not configured")
        return self.provider.model

    def embed(self, request: EmbeddingRequest) -> EmbeddingResult:
        model = self.model()
        if request.expected_model_key is not None and request.expected_model_key != model.key:
            raise EmbeddingModelChanged("The embedding model changed")
        image = None
        if request.image_key:
            data = self.storage.read(request.image_key, MAX_IMAGE_BYTES)
            if checksum(data) != request.image_checksum_sha256:
                raise ValueError("Photograph checksum does not match")
            image = prepared_image(data)
        elif request.image_base64:
            image = prepared_image(base64.b64decode(request.image_base64, validate=True))
        vector = self.provider.embed(request.text, image)
        return EmbeddingResult(
            model_key=model.key, model=model, vector=normalized(vector, model.dimensions)
        )
