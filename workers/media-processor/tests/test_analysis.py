import json

import boto3
import pytest
from botocore.stub import ANY, Stubber
from pydantic import ValidationError
from test_pipeline import MemoryStorage, NoAnalysis, TestSegmentation, job_for, photograph

from closetos_media.analysis import PROMPT, PROMPT_VERSION, BedrockAnalysis, bedrock_schema
from closetos_media.models import MetadataSuggestions
from closetos_media.pipeline import Pipeline


def suggested_values():
    values = {
        "category": "JEWELLERY",
        "subcategory": "Earrings",
        "primaryColour": "Silver",
        "secondaryColours": [],
        "pattern": None,
        "materialEstimate": "Silver coloured metal",
        "length": None,
        "formality": "Formal",
        "seasonTags": [],
        "styleTags": ["Minimal"],
        "occasionTags": ["Evening"],
        "brand": None,
        "notes": None,
    }
    return {field: {"value": value, "confidence": 0.61} for field, value in values.items()}


def bedrock_client():
    return boto3.client(
        "bedrock-runtime",
        region_name="eu-west-2",
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )


def response(content):
    return {
        "output": {"message": {"role": "assistant", "content": content}},
        "stopReason": "tool_use",
        "usage": {"inputTokens": 100, "outputTokens": 100, "totalTokens": 200},
        "metrics": {"latencyMs": 1},
    }


def test_converse_uses_strict_tool_schema_and_preserves_suggestion_provenance(monkeypatch):
    monkeypatch.setenv("BEDROCK_ANALYSIS_MODEL_ID", "configured-model")
    monkeypatch.setenv("BEDROCK_ANALYSIS_MODEL_VERSION", "configured-version")
    client = bedrock_client()
    monkeypatch.setattr("closetos_media.analysis.boto3.client", lambda *args, **kwargs: client)
    analysis = BedrockAnalysis()
    source = photograph()
    job, storage = job_for(source), MemoryStorage()
    storage.objects[job.source_key] = source
    media = Pipeline(storage, TestSegmentation(), NoAnalysis()).process(job)
    with Stubber(client) as stub:
        stub.add_response(
            "converse",
            response(
                [
                    {
                        "toolUse": {
                            "toolUseId": "metadata",
                            "name": "garment_metadata",
                            "input": suggested_values(),
                        }
                    }
                ]
            ),
            {
                "modelId": "configured-model",
                "system": [{"text": PROMPT}],
                "messages": ANY,
                "inferenceConfig": {"maxTokens": 2500, "temperature": 0},
                "toolConfig": {
                    "tools": [
                        {
                            "toolSpec": {
                                "name": "garment_metadata",
                                "description": "Garment metadata suggestions",
                                "strict": True,
                                "inputSchema": {"json": bedrock_schema()},
                            }
                        }
                    ],
                    "toolChoice": {"tool": {"name": "garment_metadata"}},
                },
            },
        )
        document = analysis.analyse(job, media, storage)
        stub.assert_no_pending_responses()
    assert document.image_id == job.image_id
    assert document.pipeline_version == job.pipeline_version
    assert document.model_id == "configured-model"
    assert document.model_version == "configured-version"
    assert document.prompt_version == PROMPT_VERSION
    assert document.suggestions.category.value == "JEWELLERY"
    assert document.suggestions.material_estimate.confidence == 0.61
    assert document.model_dump(by_alias=True)["suggestions"]["length"]["value"] is None


@pytest.mark.parametrize(
    "content",
    [
        [{"text": "Trust this unstructured response"}],
        [{"toolUse": {"toolUseId": "wrong", "name": "other_tool", "input": {}}}],
        [
            {
                "toolUse": {
                    "toolUseId": str(index),
                    "name": "garment_metadata",
                    "input": suggested_values(),
                }
            }
            for index in range(2)
        ],
    ],
)
def test_rejects_missing_wrong_or_ambiguous_tool_calls(monkeypatch, content):
    monkeypatch.setenv("BEDROCK_ANALYSIS_MODEL_ID", "configured-model")
    client = bedrock_client()
    monkeypatch.setattr("closetos_media.analysis.boto3.client", lambda *args, **kwargs: client)
    source = photograph()
    job, storage = job_for(source), MemoryStorage()
    storage.objects[job.source_key] = source
    media = Pipeline(storage, TestSegmentation(), NoAnalysis()).process(job)
    with Stubber(client) as stub:
        stub.add_response("converse", response(content))
        with pytest.raises(ValueError, match="structured suggestions"):
            BedrockAnalysis().analyse(job, media, storage)


@pytest.mark.parametrize(
    "field,value",
    [
        ("category", {"value": "JACKET", "confidence": 0.9}),
        ("brand", {"value": 42, "confidence": 0.9}),
        ("materialEstimate", {"value": "Wool", "confidence": "0.9"}),
        ("length", {"value": None, "confidence": 1.1}),
        ("length", {"value": None, "confidence": float("nan")}),
        ("length", {"value": "x" * 61, "confidence": 0.9}),
        ("seasonTags", {"value": ["Summer"] * 13, "confidence": 0.9}),
        ("styleTags", {"value": [""], "confidence": 0.9}),
        ("purchasePrice", {"value": 99, "confidence": 0.9}),
    ],
)
def test_validates_full_bounds_and_types_independently_of_provider(field, value):
    suggestions = suggested_values()
    suggestions[field] = value
    with pytest.raises(ValidationError):
        MetadataSuggestions.model_validate(suggestions)


def test_requires_every_field_and_forbids_extra_suggestion_properties():
    suggestions = suggested_values()
    del suggestions["length"]
    with pytest.raises(ValidationError):
        MetadataSuggestions.model_validate(suggestions)
    suggestions = suggested_values()
    suggestions["brand"]["source"] = "invented"
    with pytest.raises(ValidationError):
        MetadataSuggestions.model_validate(suggestions)


def test_bedrock_schema_preserves_strict_types_without_unsupported_bounds():
    schema = bedrock_schema()
    encoded = json.dumps(schema)
    for unsupported in ["minimum", "maximum", "minLength", "maxLength", "maxItems"]:
        assert unsupported not in encoded
    assert set(schema["required"]) == set(suggested_values())
    assert schema["additionalProperties"] is False
    assert all(
        definition["additionalProperties"] is False
        for definition in schema["$defs"].values()
        if definition.get("type") == "object"
    )


def test_unconfigured_analysis_never_calls_aws(monkeypatch):
    monkeypatch.delenv("BEDROCK_ANALYSIS_MODEL_ID", raising=False)
    monkeypatch.setattr(
        "closetos_media.analysis.boto3.client",
        lambda *args, **kwargs: pytest.fail("Unexpected AWS call"),
    )
    analysis = BedrockAnalysis()
    assert analysis.analyse(None, None, None) is None


@pytest.mark.parametrize(
    ("analysis_region", "expected"), [("us-east-1", "us-east-1"), (None, "eu-west-2")]
)
def test_analysis_model_region_is_independent_of_storage_region(
    monkeypatch, analysis_region, expected
):
    monkeypatch.setenv("BEDROCK_ANALYSIS_MODEL_ID", "configured-model")
    monkeypatch.setenv("AWS_REGION", "eu-west-2")
    if analysis_region:
        monkeypatch.setenv("BEDROCK_ANALYSIS_REGION", analysis_region)
    else:
        monkeypatch.delenv("BEDROCK_ANALYSIS_REGION", raising=False)
    calls = []
    monkeypatch.setattr(
        "closetos_media.analysis.boto3.client",
        lambda service, **kwargs: calls.append((service, kwargs)) or object(),
    )
    assert BedrockAnalysis().client is not None
    assert calls[0][0] == "bedrock-runtime"
    assert calls[0][1]["region_name"] == expected
    assert calls[0][1]["config"].read_timeout == 90
