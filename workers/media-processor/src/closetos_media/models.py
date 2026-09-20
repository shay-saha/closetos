import base64
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel


class WireModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class WorkflowJob(WireModel):
    job_id: UUID
    image_id: UUID
    garment_id: UUID
    wardrobe_id: UUID
    source_key: str
    output_prefix: str
    mime_type: Literal["image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"]
    expected_size: int = Field(gt=0, le=26_214_400)
    checksum_sha256: str
    pipeline_version: str = Field(pattern=r"^\d+-r[1-5]$")
    request_id: str = Field(min_length=1, max_length=200)

    @field_validator("checksum_sha256")
    @classmethod
    def checksum(cls, value: str) -> str:
        if len(base64.b64decode(value, validate=True)) != 32:
            raise ValueError("Invalid SHA-256 checksum")
        return value

    @model_validator(mode="after")
    def object_scope(self):
        # The owner is not the wardrobe UUID. Both are kept in the storage path.
        parts = self.source_key.split("/")
        if len(parts) != 7 or parts[0] != "users" or parts[2] != "garments":
            raise ValueError("Invalid source object scope")
        UUID(parts[1])
        if parts[3] != str(self.garment_id) or parts[4] != "images":
            raise ValueError("Source object belongs to another garment")
        if parts[5] != str(self.image_id) or not parts[6].startswith("original."):
            raise ValueError("Source object belongs to another photograph")
        prefix = "/".join(parts[:6]) + "/pipelines/" + self.pipeline_version + "/"
        if self.output_prefix != prefix:
            raise ValueError("Invalid output object scope")
        return self


class AssetDescriptor(WireModel):
    key: str
    checksum_sha256: str
    size: int = Field(gt=0)
    width: int = Field(gt=0, le=4096)
    height: int = Field(gt=0, le=4096)


class ProcessingResult(WireModel):
    job_id: UUID
    image_id: UUID
    pipeline_version: str
    source_checksum_sha256: str
    assets: dict[str, AssetDescriptor]
    analysis_key: str | None = None
    analysis_failure: str | None = None
    foreground_fraction: float = Field(gt=0, lt=1)
    segmentation_model: str


Confidence = Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)]


class Suggestion[T](WireModel):
    model_config = ConfigDict(strict=True)
    value: T
    confidence: Confidence


type ShortText = Annotated[str, Field(min_length=1, max_length=60)] | None
type MediumText = Annotated[str, Field(min_length=1, max_length=80)] | None
type LongerText = Annotated[str, Field(min_length=1, max_length=120)] | None
type Tags = list[Annotated[str, Field(min_length=1, max_length=60)]]


class MetadataSuggestions(WireModel):
    category: Suggestion[
        Literal[
            "TOP", "BOTTOM", "DRESS", "OUTERWEAR", "SHOES", "BAG", "JEWELLERY", "ACCESSORY", "OTHER"
        ]
    ]
    subcategory: Suggestion[MediumText]
    primary_colour: Suggestion[ShortText]
    secondary_colours: Suggestion[Annotated[Tags, Field(max_length=12)]]
    pattern: Suggestion[MediumText]
    material_estimate: Suggestion[LongerText]
    length: Suggestion[ShortText]
    style_tags: Suggestion[Annotated[Tags, Field(max_length=24)]]
    season_tags: Suggestion[Annotated[Tags, Field(max_length=12)]]
    occasion_tags: Suggestion[Annotated[Tags, Field(max_length=24)]]
    formality: Suggestion[ShortText]
    brand: Suggestion[LongerText]
    notes: Suggestion[Annotated[str, Field(min_length=1, max_length=4000)] | None]


class AnalysisDocument(WireModel):
    image_id: UUID
    pipeline_version: str
    model_id: str
    model_version: str | None = None
    prompt_version: str
    suggestions: MetadataSuggestions
