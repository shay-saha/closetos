import io
import warnings

from PIL import Image, ImageChops, ImageCms, ImageOps, ImageStat, UnidentifiedImageError
from pillow_heif import register_heif_opener

from closetos_media.models import AssetDescriptor, ProcessingResult, WorkflowJob
from closetos_media.segmentation import GarmentSegmentationPort
from closetos_media.storage import StoragePort, checksum

register_heif_opener()
Image.MAX_IMAGE_PIXELS = 24_000_000
MAX_SOURCE_BYTES = 26_214_400
FORMATS = {
    "image/jpeg": {"JPEG"},
    "image/png": {"PNG"},
    "image/webp": {"WEBP"},
    "image/heic": {"HEIF"},
    "image/heif": {"HEIF"},
}


def decode(data: bytes, job: WorkflowJob) -> Image.Image:
    if len(data) != job.expected_size or checksum(data) != job.checksum_sha256:
        raise ValueError("Photograph failed size or checksum verification")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as probe:
                if probe.format not in FORMATS[job.mime_type]:
                    raise ValueError("Photograph contents do not match its declared format")
                if min(probe.size) < 32 or probe.width * probe.height > Image.MAX_IMAGE_PIXELS:
                    raise ValueError("Photograph dimensions are outside supported limits")
                if getattr(probe, "n_frames", 1) != 1:
                    raise ValueError("Animated photographs are not supported")
                probe.verify()
            with Image.open(io.BytesIO(data)) as source:
                source.load()
                image = ImageOps.exif_transpose(source)
                profile = image.info.get("icc_profile")
                if profile:
                    alpha = image.convert("RGBA").getchannel("A")
                    image = ImageCms.profileToProfile(
                        image.convert("RGB"),
                        ImageCms.ImageCmsProfile(io.BytesIO(profile)),
                        ImageCms.createProfile("sRGB"),
                        outputMode="RGB",
                    )
                    image.putalpha(alpha)
                else:
                    image = image.convert("RGBA")
                image.thumbnail((2048, 2048), Image.Resampling.LANCZOS)
                image.info.clear()
                return image
    except (
        OSError,
        UnidentifiedImageError,
        Image.DecompressionBombWarning,
        Image.DecompressionBombError,
        ImageCms.PyCMSError,
    ) as error:
        raise ValueError("Photograph is corrupt or cannot be decoded safely") from error


def isolate(image: Image.Image, segmentation: GarmentSegmentationPort):
    mask = segmentation.mask(image)
    if mask.size != image.size or mask.mode != "L":
        raise ValueError("Segmentation returned an invalid mask")
    alpha = ImageChops.multiply(image.getchannel("A"), mask)
    fraction = ImageStat.Stat(alpha).mean[0] / 255
    if not 0.005 < fraction < 0.99:
        raise ValueError("Could not distinguish the garment from its background")
    bbox = alpha.point(lambda value: 255 if value > 8 else 0).getbbox()
    if bbox is None or min(bbox[2] - bbox[0], bbox[3] - bbox[1]) < 16:
        raise ValueError("No usable garment was found")
    image.putalpha(alpha)
    cropped = image.crop(bbox)
    padding = max(8, round(max(cropped.size) * 0.04))
    canvas = Image.new("RGBA", (cropped.width + padding * 2, cropped.height + padding * 2))
    canvas.paste(cropped, (padding, padding))
    return canvas, fraction


def transform(job: WorkflowJob, storage: StoragePort, segmentation: GarmentSegmentationPort):
    source = storage.read(job.source_key, MAX_SOURCE_BYTES)
    image, fraction = isolate(decode(source, job), segmentation)
    assets = {}
    for role, limit in {
        "isolated": 4096,
        "display": 1600,
        "card": 768,
        "thumbnail": 256,
        "mask": 4096,
    }.items():
        derivative = image.copy()
        derivative.thumbnail((limit, limit), Image.Resampling.LANCZOS)
        if role == "mask":
            derivative = derivative.getchannel("A")
        buffer = io.BytesIO()
        if role == "mask":
            derivative.save(buffer, format="PNG", optimize=True)
        else:
            derivative.save(buffer, format="WEBP", quality=90, method=6, exact=True)
        data = buffer.getvalue()
        key = job.output_prefix + role + (".png" if role == "mask" else ".webp")
        storage.write(key, data, "image/png" if role == "mask" else "image/webp")
        assets[role] = AssetDescriptor(
            key=key,
            checksum_sha256=checksum(data),
            size=len(data),
            width=derivative.width,
            height=derivative.height,
        )
    return ProcessingResult(
        job_id=job.job_id,
        image_id=job.image_id,
        pipeline_version=job.pipeline_version,
        source_checksum_sha256=job.checksum_sha256,
        assets=assets,
        foreground_fraction=fraction,
        segmentation_model=segmentation.model_name,
    )
