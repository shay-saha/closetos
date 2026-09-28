import io
from uuid import uuid4

import pytest
from PIL import Image, ImageDraw
from pydantic import ValidationError

from closetos_media.imaging import decode
from closetos_media.models import MetadataSuggestions, WorkflowJob
from closetos_media.pipeline import Pipeline
from closetos_media.storage import checksum


class MemoryStorage:
    def __init__(self):
        self.objects = {}
        self.writes = []

    def read(self, key, limit):
        data = self.objects[key]
        if len(data) > limit:
            raise ValueError("Object exceeds limit")
        return data

    def write(self, key, data, mime_type):
        self.objects[key] = data
        self.writes.append((key, mime_type))

    def optional_json(self, key):
        import json

        return json.loads(self.objects[key]) if key in self.objects else None


class TestSegmentation:
    __test__ = False
    model_name = "test-mask"
    calls = 0

    def mask(self, image):
        self.calls += 1
        mask = Image.new("L", image.size)
        ImageDraw.Draw(mask).rectangle((30, 20, image.width - 30, image.height - 20), fill=255)
        return mask


class NoAnalysis:
    def analyse(self, job, result, storage):
        return None


def photograph(image=None, format="PNG", **options):
    output = io.BytesIO()
    (image or Image.new("RGB", (360, 480), "green")).save(output, format=format, **options)
    return output.getvalue()


def job_for(data, mime="image/png"):
    owner, garment, image = uuid4(), uuid4(), uuid4()
    prefix = f"users/{owner}/garments/{garment}/images/{image}/"
    return WorkflowJob(
        job_id=uuid4(),
        image_id=image,
        garment_id=garment,
        wardrobe_id=uuid4(),
        source_key=prefix + "original.png",
        output_prefix=prefix + "pipelines/1-r1/",
        mime_type=mime,
        expected_size=len(data),
        checksum_sha256=checksum(data),
        pipeline_version="1-r1",
        request_id=str(uuid4()),
    )


def test_pipeline_produces_verified_transparent_sizes_and_reuses_completed_manifest():
    data = photograph()
    job = job_for(data)
    storage, segmentation = MemoryStorage(), TestSegmentation()
    storage.objects[job.source_key] = data
    pipeline = Pipeline(storage, segmentation, NoAnalysis())
    result = pipeline.process(job)
    assert set(result.assets) == {"isolated", "display", "card", "thumbnail", "mask"}
    assert result.analysis_failure == "ANALYSIS_NOT_CONFIGURED"
    for role, asset in result.assets.items():
        encoded = storage.objects[asset.key]
        assert asset.checksum_sha256 == checksum(encoded)
        assert asset.size == len(encoded)
        with Image.open(io.BytesIO(encoded)) as image:
            assert image.size == (asset.width, asset.height)
            if role != "mask":
                assert image.mode == "RGBA"
                assert image.getpixel((0, 0))[3] == 0
            if role == "thumbnail":
                assert max(image.size) == 256
    writes = len(storage.writes)
    assert pipeline.process(job) == result
    assert len(storage.writes) == writes
    assert segmentation.calls == 1
    assert storage.objects[job.source_key] == data


@pytest.mark.parametrize("mime", ["image/jpeg", "image/webp", "image/heic"])
def test_rejects_format_spoofing(mime):
    data = photograph()
    with pytest.raises(ValueError, match="declared format"):
        decode(data, job_for(data, mime))


def test_rejects_checksum_mismatch_and_corruption():
    data = photograph()
    with pytest.raises(ValueError, match="checksum"):
        decode(data[:-1], job_for(data))
    broken = b"not a photograph"
    with pytest.raises(ValueError, match="corrupt"):
        decode(broken, job_for(broken))


def test_corrects_exif_orientation_and_strips_metadata():
    image = Image.new("RGB", (80, 120), "red")
    exif = Image.Exif()
    exif[274] = 6
    exif[315] = "private author"
    data = photograph(image, "JPEG", exif=exif)
    decoded = decode(data, job_for(data, "image/jpeg"))
    assert decoded.size == (120, 80)
    assert decoded.getexif() == {}
    assert decoded.info == {}


def test_rejects_pixel_resource_limit_before_loading(monkeypatch):
    data = photograph()
    monkeypatch.setattr(Image, "MAX_IMAGE_PIXELS", 100_000)
    with pytest.raises(ValueError):
        decode(data, job_for(data))


def test_preserves_existing_alpha():
    image = Image.new("RGBA", (360, 480), (255, 0, 0, 128))
    data = photograph(image)
    job = job_for(data)
    storage = MemoryStorage()
    storage.objects[job.source_key] = data
    result = Pipeline(storage, TestSegmentation(), NoAnalysis()).process(job)
    with Image.open(io.BytesIO(storage.objects[result.assets["isolated"].key])) as isolated:
        assert isolated.getchannel("A").getextrema() == (0, 128)


@pytest.mark.parametrize("fill", [0, 255])
def test_rejects_empty_and_opaque_masks(fill):
    class InvalidSegmentation:
        model_name = "invalid-mask"

        def mask(self, image):
            return Image.new("L", image.size, fill)

    data = photograph()
    job = job_for(data)
    storage = MemoryStorage()
    storage.objects[job.source_key] = data
    with pytest.raises(ValueError, match="distinguish"):
        Pipeline(storage, InvalidSegmentation(), NoAnalysis()).process(job)
    assert not storage.writes


def test_analysis_failure_preserves_successful_images_without_logging_model_content(caplog):
    class FailedAnalysis:
        def analyse(self, job, result, storage):
            raise RuntimeError("Private model output\nFORGED_LOG_EVENT")

    data = photograph()
    job = job_for(data)
    storage = MemoryStorage()
    storage.objects[job.source_key] = data
    result = Pipeline(storage, TestSegmentation(), FailedAnalysis()).process(job)
    assert result.analysis_failure == "ANALYSIS_UNAVAILABLE"
    assert len(result.assets) == 5
    assert result.analysis_key is None
    assert str(job.job_id) in caplog.text
    assert "RuntimeError" in caplog.text
    assert "Private model output" not in caplog.text
    assert "FORGED_LOG_EVENT" not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)


def test_source_and_output_keys_cannot_cross_tenant_or_image_boundaries():
    data = photograph()
    job = job_for(data).model_dump()
    job["output_prefix"] = "users/another-owner/garments/another-garment/"
    with pytest.raises(ValidationError, match="scope"):
        WorkflowJob.model_validate(job)


def test_suggestions_require_valid_confidence_and_reject_unrecognised_fields():
    schema = MetadataSuggestions.model_json_schema(by_alias=True)
    assert set(schema["required"]) == set(schema["properties"])
    suggestions = {}
    for field in schema["properties"]:
        value = (
            "TOP"
            if field == "category"
            else ([] if field.endswith("Tags") or field == "secondaryColours" else None)
        )
        suggestions[field] = {"value": value, "confidence": 0.5}
    MetadataSuggestions.model_validate(suggestions)
    suggestions["brand"]["confidence"] = 1.1
    with pytest.raises(ValidationError):
        MetadataSuggestions.model_validate(suggestions)
    suggestions["brand"]["confidence"] = float("nan")
    with pytest.raises(ValidationError):
        MetadataSuggestions.model_validate(suggestions)
    suggestions["brand"]["confidence"] = 0.1
    suggestions["purchasePrice"] = {"value": 500, "confidence": 1}
    with pytest.raises(ValidationError):
        MetadataSuggestions.model_validate(suggestions)
