import asyncio
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.domains.analysis.context.company_profile import BISTELLIGENCE_PROFILE
from app.domains.analysis.proposals.writer import (
    ProposalWriter,
    ProposalWriteRequest,
    ProposalWriteResponse,
    company_evidence,
    selected_outline,
    verify_outline,
    verify_writing,
)

TITLES = ["1. 사업 필요성", "2. 수행 역량", "3. 추진 방법", "4. 기대 효과"]
TEXT = "\n".join(f"{title}\n세부 내용을 작성합니다." for title in TITLES)
BODY = (
    "당사는 제조 현장의 데이터를 통합하고 AI 모델을 운영하는 역량을 보유하고 있습니다. "
    "반도체 공정의 데이터 수집과 품질 확인을 시작으로 현장 적용 범위를 구체화하겠습니다. "
    "분석 결과를 담당자가 확인할 수 있는 업무 흐름으로 구성하고 운영 과정의 피드백을 "
    "반영해 개선하겠습니다. 고객 데이터의 접근 권한과 보안 요건을 먼저 대조하겠습니다. "
    "이후 모델 검증 결과와 운영 기록을 정리해 현장 담당자가 활용할 수 있는 산출물로 제공하겠습니다."
)


BODY = BODY + "\n\n" + BODY


def request(text=TEXT):
    return ProposalWriteRequest(
        title="제조 AI 공고",
        noticeText="반도체 제조 AI 실증을 지원합니다.",
        fileName="사업계획서.hwpx",
        templateText=text,
    )


def outline_data(titles=TITLES):
    return {
        "is_writing_template": True,
        "sections": [
            {
                "title": title,
                "source_quote": f"{title}\n세부 내용을 작성합니다.",
                "selection_reason": "사업의 구체적인 수행 내용을 평가하는 항목입니다.",
            }
            for title in titles
        ],
    }


def writing():
    return {
        "sections": [
            {
                "section_id": index,
                "body": BODY,
                "company_evidence_ids": [1],
                "confirmation_items": ["공고별 고객 데이터 접근 권한을 확인합니다."],
            }
            for index in range(1, 5)
        ]
    }


def settings():
    return SimpleNamespace(
        proposal_model_name="test-model", api_key="test", proposal_timeout_seconds=180
    )


def test_outline_preserves_real_titles_order_and_rejects_invented_heading_or_quote():
    result = verify_outline(outline_data(list(reversed(TITLES))), TEXT)
    assert [section.title for section in result] == TITLES
    for field in ("title", "source_quote"):
        invalid = outline_data()
        invalid["sections"][0][field] = "없는 양식 항목입니다."
        with pytest.raises(ValueError):
            verify_outline(invalid, TEXT)


def test_duplicate_headings_and_non_template_are_not_generated():
    with pytest.raises(ValueError):
        verify_outline(outline_data([TITLES[0], TITLES[0]]), TEXT)
    assert verify_outline({"is_writing_template": False, "sections": []}, TEXT) == []


def test_four_sections_keep_source_quotes_and_demo_flag():
    response = verify_writing(
        writing(),
        verify_outline(outline_data(), TEXT),
        ["services[0]: 제조 AI 운영"],
        "사업계획서.hwpx",
        True,
    )
    assert response.status == "COMPLETED"
    assert response.uses_demo_profile
    assert [section.title for section in response.sections] == TITLES
    assert response.sections[0].company_evidence == ["제조 AI 운영"]
    assert response.sections[0].source_quote.startswith(TITLES[0])
    assert "sourceQuote" in response.model_dump(by_alias=True)["sections"][0]


@pytest.mark.parametrize("change", ["missing", "duplicate", "evidence", "tone", "instruction"])
def test_writing_rejects_missing_duplicate_invented_evidence_and_bad_tone(change):
    output = writing()
    if change == "missing":
        output["sections"].pop()
    elif change == "duplicate":
        output["sections"][1]["section_id"] = 1
    elif change == "evidence":
        output["sections"][0]["company_evidence_ids"] = [99]
    elif change == "tone":
        output["sections"][0]["body"] += "\n현장 적용을 권장함."
    else:
        output["sections"][0]["body"] += "\n귀사는 내용을 작성해야 합니다."
    with pytest.raises(ValueError):
        verify_writing(
            output,
            verify_outline(outline_data(), TEXT),
            ["services[0]: 제조 AI 운영"],
            "양식",
            False,
        )


def test_less_than_four_real_sections_are_disclosed_and_input_limit_is_enforced():
    output = writing()
    output["sections"] = output["sections"][:2]
    response = verify_writing(
        output,
        verify_outline(outline_data(TITLES[:2]), TEXT),
        ["services[0]: 제조 AI 운영"],
        "양식",
        False,
    )
    assert len(response.sections) == 2
    assert "4개보다 적어" in response.message
    with pytest.raises(ValidationError):
        request("가" * 80_001)


def test_profile_evidence_keeps_unknown_fields_and_excludes_legacy_demo_data():
    limitation = "고객 데이터의 외부 전송 승인 문서는 아직 확보하지 못했습니다."
    capacity = "공유 GPU를 활용하며 신규 개발 작업의 가용 인력은 제한적입니다."
    evidence = company_evidence(
        {
            "companyName": "회사",
            "unknownFields": [limitation],
            "demoProfile": {"id": "DEMO-NEED", "capacity": capacity},
        }
    )
    assert f"unknownFields[0]: {limitation}" in evidence
    assert not any("demoProfile" in value or capacity in value for value in evidence)
    assert not any("DEMO-NEED" in value for value in evidence)


def test_single_flight_cache_profile_change_and_failed_result_not_cached():
    async def scenario():
        writer = ProposalWriter()
        compose = AsyncMock(return_value=ProposalWriteResponse(status="COMPLETED"))
        with (
            patch.object(writer, "_compose", compose),
            patch(
                "app.domains.analysis.proposals.writer.AnalysisSettings.from_env",
                return_value=settings(),
            ),
        ):
            first, second = await asyncio.gather(writer.write(request()), writer.write(request()))
            assert first is second
            assert (await writer.write(request())).status == "COMPLETED"
            assert compose.await_count == 1
            profile = compose.call_args.args[1]
            assert "demoProfile" not in profile
            assert profile["verifiedFacts"] == list(BISTELLIGENCE_PROFILE.verified_facts)
            updated = replace(BISTELLIGENCE_PROFILE, description="수정된 실제 회사 소개입니다.")
            with patch("app.domains.analysis.proposals.writer.BISTELLIGENCE_PROFILE", updated):
                await writer.write(request())
            assert compose.await_count == 2
            compose.side_effect = RuntimeError("sensitive internal data")
            failed = await writer.write(request(TEXT + "\n다른 내용"))
            assert failed.status == "UNAVAILABLE"
            assert "sensitive" not in failed.message
            await writer.write(request(TEXT + "\n다른 내용"))
            assert compose.await_count == 4
            assert not writer.inflight

    asyncio.run(scenario())


def test_two_stage_generation_and_one_bounded_validation_retry():
    async def scenario():
        writer = ProposalWriter()
        outline_model = AsyncMock()
        outline_model.ainvoke.return_value = {
            "is_writing_template": True,
            "sections": [
                {
                    "heading_line_ids": [index],
                    "selection_reason": "사업 수행 내용을 평가하는 항목입니다.",
                }
                for index in (1, 3, 5, 7)
            ],
        }
        bad = writing()
        bad["sections"][0]["body"] += "\n내용 수정 권장함."
        writing_model = AsyncMock()
        writing_model.ainvoke.side_effect = [bad, writing()]
        model = SimpleNamespace(
            with_structured_output=lambda schema: (
                outline_model if schema.__name__ == "TemplateSelectionOutput" else writing_model
            )
        )
        with patch("app.domains.analysis.proposals.writer.ChatOpenAI", return_value=model):
            profile = {
                "services": ["반도체 제조 현장의 AI 모델을 개발하고 운영하는 서비스를 제공합니다."]
            }
            result = await writer._compose(request(), profile, settings(), "2026-09-08")
        assert result.status == "COMPLETED"
        assert result.uses_demo_profile is False
        assert "SYNTHETIC_DEMO" not in str(writing_model.ainvoke.call_args.args)
        assert "demoProfile" not in str(writing_model.ainvoke.call_args.args)
        assert outline_model.ainvoke.await_count == 1
        assert writing_model.ainvoke.await_count == 2

    asyncio.run(scenario())


def test_non_template_does_not_call_writer_and_invalid_outline_is_not_published():
    async def scenario():
        writer = ProposalWriter()
        model = AsyncMock()
        model.ainvoke.return_value = {"is_writing_template": False, "sections": []}
        with patch("app.domains.analysis.proposals.writer.ChatOpenAI") as chat:
            chat.return_value.with_structured_output.return_value = model
            result = await writer._compose(
                request("개인정보 처리 동의서\n서명"), {}, settings(), "2026-09-08"
            )
        assert result.status == "NEEDS_TEMPLATE"
        assert model.ainvoke.await_count == 1

    asyncio.run(scenario())


def test_timeout_returns_safe_retryable_failure_and_is_not_cached():
    async def scenario():
        writer = ProposalWriter()
        short = settings()
        short.proposal_timeout_seconds = 0.01

        async def slow(*args):
            await asyncio.sleep(1)

        with (
            patch.object(writer, "_compose", side_effect=slow),
            patch(
                "app.domains.analysis.proposals.writer.AnalysisSettings.from_env",
                return_value=short,
            ),
        ):
            result = await writer.write(request())
        assert result.status == "UNAVAILABLE"
        assert not writer.cache
        assert not writer.inflight

    asyncio.run(scenario())


def test_endpoint_uses_camel_case_contract_and_rejects_empty_input():
    from app.main import app

    client = TestClient(app)
    with patch("app.domains.analysis.api.proposal_writer.write", new_callable=AsyncMock) as write:
        write.return_value = ProposalWriteResponse(status="NEEDS_TEMPLATE", file_name="양식")
        response = client.post(
            "/internal/monitoring/proposal-write", json=request().model_dump(by_alias=True)
        )
        assert response.status_code == 200
        assert response.json()["fileName"] == "양식"
        assert response.json()["usesDemoProfile"] is False
        assert client.post("/internal/monitoring/proposal-write", json={}).status_code == 422


def test_line_selection_keeps_original_typo_and_multiline_title_without_model_rewriting():
    text = (
        "다. 실증의 위한 구역, 기간, 규모\n기간과 구역을 기술합니다.\n"
        "사업실시(실증)\n항목 및 내용\n구체적인 계획을 기술합니다."
    )
    output = {
        "is_writing_template": True,
        "sections": [
            {"heading_line_ids": [3, 4], "selection_reason": "실제 수행 계획이 중요합니다."},
            {"heading_line_ids": [1], "selection_reason": "사업 규모와 기간을 확인합니다."},
        ],
    }
    result = selected_outline(output, text)
    assert result[0].title == "다. 실증의 위한 구역, 기간, 규모"
    assert result[1].title == "사업실시(실증)\n항목 및 내용"
    assert result[1].source_quote == "사업실시(실증)\n항목 및 내용\n구체적인 계획을 기술합니다."
    output["sections"][0]["heading_line_ids"] = [99]
    with pytest.raises(ValueError):
        selected_outline(output, text)


def test_technical_test_scenarios_are_valid_proposal_prose():
    output = writing()
    output["sections"][0]["body"] += "\n작업자 접근과 가림 상황을 시험 시나리오로 구성하겠습니다."
    result = verify_writing(
        output, verify_outline(outline_data(), TEXT), ["services[0]: 제조 AI 운영"], "양식", True
    )
    assert result.status == "COMPLETED"


def test_model_schema_parse_failure_gets_one_bounded_rewrite():
    async def scenario():
        from app.domains.analysis.proposals.writer import WrittenProposal

        invalid = writing()
        invalid["sections"][0]["body"] = "너무 짧은 본문입니다."
        with pytest.raises(ValidationError) as failure:
            WrittenProposal.model_validate(invalid)
        outline = AsyncMock()
        outline.ainvoke.return_value = {
            "is_writing_template": True,
            "sections": [
                {"heading_line_ids": [index], "selection_reason": "사업 수행 내용을 평가합니다."}
                for index in (1, 3, 5, 7)
            ],
        }
        draft = AsyncMock()
        draft.ainvoke.side_effect = [failure.value, writing()]
        model = SimpleNamespace(
            with_structured_output=lambda schema: (
                outline if schema.__name__ == "TemplateSelectionOutput" else draft
            )
        )
        with patch("app.domains.analysis.proposals.writer.ChatOpenAI", return_value=model):
            profile = {"services": ["반도체 제조 현장에서 사용할 AI 모델을 개발하고 운영합니다."]}
            result = await ProposalWriter()._compose(request(), profile, settings(), "2026-09-08")
        assert result.status == "COMPLETED"
        assert draft.ainvoke.await_count == 2

    asyncio.run(scenario())


def test_submission_prose_removes_internal_evidence_notes_and_demo_modifier():
    output = writing()
    output["sections"][0]["body"] += (
        "\n제조 현장의 운영 경험을 보유하고 있습니다(증거: 관련 사례 보유)."
        "\n데모 프로필 기준으로 내부 일정과 인력 배정을 검토하겠습니다."
        "\n실증 결과는 데모 영상으로 정리하겠습니다."
    )
    result = verify_writing(
        output, verify_outline(outline_data(), TEXT), ["services[0]: 제조 AI 운영"], "양식", True
    )
    body = result.sections[0].body
    assert "(증거:" not in body
    assert "데모 프로필" not in body
    assert "내부 일정과 인력 배정을 검토하겠습니다." in body
    assert "데모 영상" in body
    assert result.uses_demo_profile is True

def test_duplicate_table_extraction_orders_by_heading_not_later_quote_occurrence():
    text = (
        "가. 목적 목차 나. 방법 목차 다. 기간 목차\n"
        "다. 기간\n기간 설명입니다.\n가. 목적\n목적 설명입니다.\n나. 방법\n방법 설명입니다."
    )
    output = {"is_writing_template": True, "sections": [
        {"title": "다. 기간", "source_quote": "다. 기간\n기간 설명입니다.",
         "selection_reason": "수행 기간을 확인합니다."},
        {"title": "가. 목적", "source_quote": "가. 목적\n목적 설명입니다.",
         "selection_reason": "실증 목적을 확인합니다."},
        {"title": "나. 방법", "source_quote": "나. 방법\n방법 설명입니다.",
         "selection_reason": "검증 방법을 확인합니다."},
    ]}
    result = verify_outline(output, text)
    assert [item.title for item in result] == ["가. 목적", "나. 방법", "다. 기간"]


def test_regeneration_bypasses_completed_cache_but_shares_inflight_request():
    async def scenario():
        writer = ProposalWriter()
        compose = AsyncMock(return_value=ProposalWriteResponse(status="COMPLETED"))
        original = request()
        rewrite = original.model_copy(update={"generation_id": "new-request"})
        with (
            patch.object(writer, "_compose", compose),
            patch(
                "app.domains.analysis.proposals.writer.AnalysisSettings.from_env",
                return_value=settings(),
            ),
        ):
            await writer.write(original)
            assert len(writer.cache) == 1
            first, second = await asyncio.gather(writer.write(rewrite), writer.write(rewrite))
            assert first is second
            assert compose.await_count == 2
            await writer.write(rewrite.model_copy(update={"generation_id": "another-request"}))
            assert compose.await_count == 3
            assert len(writer.cache) == 1
            await writer.write(original)
            assert compose.await_count == 3
            compose.side_effect = RuntimeError("test failure")
            assert (await writer.write(rewrite)).status == "UNAVAILABLE"
            assert len(writer.cache) == 1
            assert not writer.inflight

    asyncio.run(scenario())


def test_regeneration_feedback_and_previous_body_reach_writer_without_extra_call():
    import json

    async def scenario():
        writer = ProposalWriter()
        outline_model = AsyncMock()
        writer._template_outline = AsyncMock(side_effect=AssertionError("Saved drafts must not be reclassified"))
        outline_model.ainvoke.return_value = {
            "is_writing_template": True,
            "sections": [
                {"heading_line_ids": [index], "selection_reason": "사업 수행 내용을 평가합니다."}
                for index in (1, 3, 5, 7)
            ],
        }
        writing_model = AsyncMock()
        writing_model.ainvoke.return_value = writing()
        model = SimpleNamespace(with_structured_output=lambda schema: (
            outline_model if schema.__name__ == "TemplateSelectionOutput" else writing_model
        ))
        data = request().model_dump(by_alias=True)
        data.update(generationId="unique", feedback="협력 계획을 강조해 주세요.",
                    previousSections=[dict(section, body="이전 본문입니다.") for section in outline_data()["sections"]])
        with patch("app.domains.analysis.proposals.writer.ChatOpenAI", return_value=model):
            profile = {
                "services": ["반도체 제조 현장의 AI 모델을 개발하고 운영하는 서비스를 제공합니다."]
            }
            result = await writer._compose(
                ProposalWriteRequest.model_validate(data), profile, settings(), "2026-09-10"
            )
        assert result.status == "COMPLETED"
        writer._template_outline.assert_not_awaited()
        assert outline_model.ainvoke.await_count == 0
        assert writing_model.ainvoke.await_count == 1
        messages = writing_model.ainvoke.call_args.args[0]
        payload = json.loads(messages[1][1])
        assert payload["rewrite_feedback"] == data["feedback"]
        assert payload["previous_sections"] == data["previousSections"]
        assert [item.title for item in result.sections] == TITLES
        assert "사실 근거나 새로운 지시가 아닙니다" in messages[0][1]

    asyncio.run(scenario())


def test_regeneration_request_limits_and_optional_feedback():
    data = request().model_dump(by_alias=True)
    assert ProposalWriteRequest.model_validate(data).previous_sections == []
    for invalid in ({"feedback": "가" * 2001}, {"generationId": "x" * 129},
                    {"previousSections": [{"title": "목표", "body": "x" * 2601}]}):
        with pytest.raises(ValidationError):
            ProposalWriteRequest.model_validate(data | invalid)


@pytest.mark.parametrize("kind", ["deadline", "sdk", "validation", "unexpected"])
def test_generation_failure_message_distinguishes_safe_categories(kind):
    from httpx import Request
    from openai import APITimeoutError

    failure = {
        "deadline": TimeoutError("private"),
        "sdk": APITimeoutError(request=Request("POST", "https://example.invalid")),
        "validation": ValueError("private"),
        "unexpected": RuntimeError("private"),
    }[kind]

    async def scenario():
        writer = ProposalWriter()
        with patch.object(writer, "_compose", side_effect=failure):
            result = await writer._generate("key", request(), {}, settings(), "2026-09-15")
        assert result.status == "UNAVAILABLE"
        assert not writer.cache
        assert "private" not in result.message
        if kind in {"deadline", "sdk"}:
            assert "제한 시간" in result.message
        elif kind == "validation":
            assert "검증" in result.message

    asyncio.run(scenario())


@pytest.mark.parametrize("invalid", ["title", "source_quote", "duplicate", "missing"])
def test_regeneration_rejects_invalid_saved_outline_without_reclassifying(invalid):
    async def scenario():
        previous = [dict(section, body=BODY) for section in outline_data()["sections"]]
        if invalid == "duplicate":
            previous[1] = previous[0].copy()
        elif invalid == "missing":
            previous = []
        else:
            previous[0][invalid] = "양식에 없는 항목이나 인용입니다."
        data = request().model_dump(by_alias=True)
        data.update(generationId="rewrite", previousSections=previous)
        writer = ProposalWriter()
        writer._template_outline = AsyncMock(side_effect=AssertionError("No reclassification"))
        model = AsyncMock()
        with patch("app.domains.analysis.proposals.writer.ChatOpenAI", return_value=model):
            with pytest.raises(ValueError):
                await writer._compose(ProposalWriteRequest.model_validate(data), {}, settings(), "2026-09-16")
        writer._template_outline.assert_not_awaited()
        model.with_structured_output.assert_not_called()
    asyncio.run(scenario())
