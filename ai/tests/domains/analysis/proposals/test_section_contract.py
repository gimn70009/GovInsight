"""Exercise the exact per-request structured response, not an unconstrained model stub."""

import asyncio
import copy
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
import pytest
from langchain_core.utils.function_calling import convert_to_openai_function
from langchain_openai import ChatOpenAI
from pydantic import ValidationError

from app.domains.analysis.proposals.writer import (
    ProposalWriter,
    verify_outline,
    writing_schema,
)
from tests.domains.analysis.proposals.test_writer import (
    BODY,
    TITLES,
    outline_data,
    request,
    settings,
    writing,
)

PROFILE = {"service": "제조 현장의 데이터 통합과 AI 모델 운영 서비스를 제공합니다."}


def slots(ids=range(1, 5)):
    return {
        f"section_{index}": {key: value for key, value in item.items() if key != "section_id"}
        for index, item in enumerate(writing()["sections"], 1) if index in ids
    }


@pytest.mark.parametrize("ids", [(1,), (1, 2), (1, 2, 3, 4), (2, 4)])
def test_schema_requires_exact_sections_and_keeps_verified_identity(ids):
    schema = writing_schema(ids)
    api_schema = convert_to_openai_function(schema, strict=True)["parameters"]
    assert api_schema["required"] == [f"section_{index}" for index in ids]
    assert api_schema["additionalProperties"] is False
    result = schema.model_validate(slots(ids)).as_proposal()
    assert [item["section_id"] for item in result["sections"]] == list(ids)
    missing = slots(ids)
    missing.pop(f"section_{ids[-1]}")
    with pytest.raises(ValidationError):
        schema.model_validate(missing)
    unexpected = slots(ids)
    unexpected["section_99"] = unexpected[f"section_{ids[0]}"]
    with pytest.raises(ValidationError):
        schema.model_validate(unexpected)
    # A second, contradictory ID cannot relabel a body inside its fixed field.
    relabeled = slots(ids)
    relabeled[f"section_{ids[0]}"]["section_id"] = 4
    with pytest.raises(ValidationError):
        schema.model_validate(relabeled)


async def compose_with_schema(responses, count=4, public=False):
    calls = []
    responses = iter(responses)

    def structured(schema):
        async def invoke(messages):
            calls.append((schema, copy.deepcopy(messages)))
            # Match the typed parsing performed by LangChain's Pydantic output parser.
            return schema.model_validate(next(responses))
        return SimpleNamespace(ainvoke=invoke)

    writer = ProposalWriter()
    req = request("\n".join(f"{title}\n세부 내용을 작성합니다." for title in TITLES[:count]))
    outline = verify_outline(outline_data(TITLES[:count]), req.template_text)
    with (
        patch("app.domains.analysis.proposals.writer.ChatOpenAI",
              return_value=SimpleNamespace(with_structured_output=structured)),
        patch.object(writer, "_template_outline", AsyncMock(return_value=(outline, 0))),
        patch("app.domains.analysis.proposals.writer.AnalysisSettings.from_env",
              return_value=settings()),
    ):
        if public:
            result = await writer.write(req)
            assert not writer.cache
        else:
            result = await writer._compose(req, PROFILE, settings(), "2026-09-18")
    return result, calls


@pytest.mark.parametrize("count", [1, 2, 4])
def test_cached_outline_generates_every_selected_section(count):
    result, calls = asyncio.run(compose_with_schema([slots(range(1, count + 1))], count))
    assert result.status == "COMPLETED"
    assert [section.title for section in result.sections] == TITLES[:count]
    assert len(calls) == 1
    assert "section_1" in calls[0][0].model_fields


def test_short_four_section_response_is_retained_and_repaired_without_losing_sections():
    first = slots()
    first["section_3"]["body"] = "당사는 제공된 근거를 확인합니다."
    first["section_4"]["body"] = "당사는 추진 계획을 마련합니다."
    result, calls = asyncio.run(compose_with_schema([first, slots()]))
    assert result.status == "COMPLETED"
    assert [section.title for section in result.sections] == TITLES
    assert len(calls) == 2
    assert set(calls[1][0].model_fields) == {f"section_{index}" for index in range(1, 5)}
    rejected = json.loads(calls[1][1][-2][1])
    assert rejected["sections"][2]["body"] == first["section_3"]["body"]
    hint = calls[1][1][-1][1]
    assert '"section_id": 3' in hint and '"section_id": 4' in hint
    assert f'"body_characters": {len(first["section_4"]["body"])}' in hint
    assert "400~2,600" in hint and "모든 항목" in hint


def test_style_retry_requests_only_invalid_slots_and_preserves_valid_bodies_and_metadata():
    first = slots()
    first["section_4"]["body"] += "\n검토 필요함."
    repair = slots([4])
    repair["section_4"]["company_evidence_ids"] = [999]
    repair["section_4"]["confirmation_items"] = ["changed metadata"]
    result, calls = asyncio.run(compose_with_schema([first, repair]))
    assert result.status == "COMPLETED"
    assert list(calls[1][0].model_fields) == ["section_4"]
    assert all(section.body == BODY for section in result.sections)
    assert result.sections[3].confirmation_items == first["section_4"]["confirmation_items"]
    assert result.sections[3].company_evidence == result.sections[0].company_evidence


def test_incomplete_structured_response_can_retry_but_is_never_published(caplog):
    incomplete = slots([1, 2, 3])
    result, calls = asyncio.run(compose_with_schema([incomplete, slots()]))
    assert result.status == "COMPLETED"
    assert len(calls) == 2
    result, calls = asyncio.run(compose_with_schema([incomplete, incomplete], public=True))
    assert result.status == "UNAVAILABLE" and result.sections == []
    assert len(calls) == 2
    assert BODY not in caplog.text


def test_repeated_short_responses_fail_closed_with_two_body_calls(caplog):
    short = slots()
    short["section_3"]["body"] = "PRIVATE_SHORT_DRAFT"
    result, calls = asyncio.run(compose_with_schema([short, short], public=True))
    assert result.status == "UNAVAILABLE" and not result.sections
    assert len(calls) == 2
    assert "PRIVATE_SHORT_DRAFT" not in caplog.text


@pytest.mark.parametrize("ids", [(1, 2, 3, 4), (4,)])
def test_real_langchain_sdk_transport_requires_named_slots_and_parses_response(ids):
    async def scenario():
        schema = writing_schema(ids)
        requests = []

        def handler(http_request):
            payload = json.loads(http_request.content)
            requests.append(payload)
            return httpx.Response(200, json={
                "id": "test-completion", "object": "chat.completion", "created": 0,
                "model": "gpt-5-mini",
                "choices": [{"index": 0, "finish_reason": "stop", "message": {
                    "role": "assistant", "content": json.dumps(slots(ids)), "refusal": None,
                }}],
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            model = ChatOpenAI(model="gpt-5-mini", api_key="test", http_async_client=client)
            result = await model.with_structured_output(schema).ainvoke("Generate a test draft.")
        response_format = requests[0]["response_format"]["json_schema"]
        assert response_format["strict"] is True
        assert response_format["schema"]["required"] == [f"section_{index}" for index in ids]
        assert isinstance(result, schema)
        assert [item["section_id"] for item in result.as_proposal()["sections"]] == list(ids)

    asyncio.run(scenario())


def test_short_style_repair_preserves_all_four_original_sections():
    original = slots()
    original["section_3"]["body"] += " 성과 활용 검토함."
    short_repair = slots([3])
    short_repair["section_3"]["body"] = "성과 활용을 검토하겠습니다."
    result, calls = asyncio.run(compose_with_schema([original, short_repair]))
    assert result.status == "COMPLETED"
    assert len(result.sections) == 4
    assert [section.body for section in result.sections] == [
        original[f"section_{index}"]["body"] for index in range(1, 5)
    ]
    assert list(calls[1][0].model_fields) == ["section_3"]
    assert "문장 표현" in result.message
    assert len(calls) == 2
