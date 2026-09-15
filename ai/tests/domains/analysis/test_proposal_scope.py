"""Integration boundaries for content-based template inspection (model calls are mocked)."""
import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.domains.analysis.proposal_writer import (
    OUTLINE_INSTRUCTIONS, ProposalWriter, TemplateInspectRequest, TemplateSelectionOutput,
)
from tests.domains.analysis.test_proposal_writer import request, settings, writing
from tests.domains.analysis.test_proposal_reliability import PROFILE, selection


def test_business_plan_with_notice_in_name_reaches_content_classifier_and_generation_reuses_it():
    async def scenario():
        data = request().model_copy(update={
            "file_name": "붙임2. 사업계획서 및 제출 서류 서식(하반기 공고).hwp",
        })
        selector = SimpleNamespace(ainvoke=AsyncMock(return_value=selection(1, 3, 5, 7)))
        body = SimpleNamespace(ainvoke=AsyncMock(return_value=writing()))
        model = SimpleNamespace(with_structured_output=lambda schema:
                                selector if schema == TemplateSelectionOutput else body)
        writer = ProposalWriter()
        with (
            patch("app.domains.analysis.proposal_writer.AnalysisSettings.from_env", return_value=settings()),
            patch("app.domains.analysis.proposal_writer.ChatOpenAI", return_value=model),
        ):
            result = await writer.inspect(data)
            assert result.status == "WRITABLE"
            assert result.section_titles == ["1. 사업 필요성", "2. 수행 역량", "3. 추진 방법", "4. 기대 효과"]
            payload = json.loads(selector.ainvoke.call_args.args[0][1][1])
            assert payload["document_text"] == data.template_text
            assert payload["file_name"] == data.file_name
            saved = await writer._compose(data, PROFILE, settings(), "2026-09-15")
            assert saved.status == "COMPLETED"
            writer.template_cache.clear()
            selector.ainvoke.return_value = {"is_writing_template": False, "sections": []}
            assert (await writer.inspect(data)).status == "NOT_WRITABLE"
            rewrite = type(data).model_validate(data.model_dump() | {
                "generation_id": "new",
                "previous_sections": [section.model_dump() for section in saved.sections],
            })
            regenerated = await writer._compose(rewrite, PROFILE, settings(), "2026-09-15")
            assert regenerated.status == "COMPLETED"
            assert [item.title for item in regenerated.sections] == [item.title for item in saved.sections]
        assert selector.ainvoke.await_count == 2
        assert body.ainvoke.await_count == 2
    asyncio.run(scenario())


@pytest.mark.parametrize("name", ["지원사업 FAQ.pdf", "사업계획서 양식.hwp", "알 수 없는 파일.pdf"])
def test_negative_content_decision_blocks_body_regardless_of_filename(name):
    async def scenario():
        data = request().model_copy(update={
            "file_name": name,
            "template_text": "자주 묻는 질문\nQ. 사업계획서는 어떻게 작성하나요?\nA. 사업 내용을 작성합니다.",
        })
        selector = SimpleNamespace(ainvoke=AsyncMock(return_value=selection(is_template=False)))
        body = SimpleNamespace(ainvoke=AsyncMock())
        model = SimpleNamespace(with_structured_output=lambda schema:
                                selector if schema == TemplateSelectionOutput else body)
        writer = ProposalWriter()
        with (
            patch("app.domains.analysis.proposal_writer.AnalysisSettings.from_env", return_value=settings()),
            patch("app.domains.analysis.proposal_writer.ChatOpenAI", return_value=model),
        ):
            assert (await writer.inspect(data)).status == "NOT_WRITABLE"
            assert (await writer._compose(data, PROFILE, settings(), "2026-09-15")).status == "NEEDS_TEMPLATE"
        assert selector.ainvoke.await_count == 1
        body.ainvoke.assert_not_called()
    asyncio.run(scenario())


@pytest.mark.parametrize("error", [TimeoutError("private"), RuntimeError("private"), ValueError("private")])
def test_inspection_failure_is_unknown_not_negative_and_can_be_retried(error):
    async def scenario():
        writer = ProposalWriter()
        selector = AsyncMock(side_effect=[error, ([SimpleNamespace(title="사업 목표")], 1)])
        with (
            patch("app.domains.analysis.proposal_writer.AnalysisSettings.from_env", return_value=settings()),
            patch("app.domains.analysis.proposal_writer.ChatOpenAI"),
            patch.object(writer, "_select_outline", selector),
        ):
            result = await writer.inspect(request())
            assert result.status == "UNAVAILABLE"
            assert "private" not in result.message
            assert not writer.template_cache
            assert (await writer.inspect(request())).status == "WRITABLE"
        assert not writer.template_inflight
    asyncio.run(scenario())


def test_concurrent_inspection_is_shared_and_content_change_invalidates_cache():
    async def scenario():
        writer = ProposalWriter()
        async def selected(*args):
            await asyncio.sleep(0)
            return [SimpleNamespace(title="사업 목표")], 1
        with (
            patch("app.domains.analysis.proposal_writer.AnalysisSettings.from_env", return_value=settings()),
            patch("app.domains.analysis.proposal_writer.ChatOpenAI"),
            patch.object(writer, "_select_outline", side_effect=selected) as selector,
        ):
            first, second = await asyncio.gather(writer.inspect(request()), writer.inspect(request()))
            assert first == second
            assert selector.await_count == 1
            await writer.inspect(request().model_copy(update={"template_text": "변경된 내용"}))
            assert selector.await_count == 2
            with patch("app.domains.analysis.proposal_writer.time.monotonic", return_value=float("inf")):
                await writer.inspect(request())
            assert selector.await_count == 3
    asyncio.run(scenario())


def test_invented_heading_never_becomes_a_writable_or_negative_cached_result():
    async def scenario():
        selector = SimpleNamespace(ainvoke=AsyncMock(return_value=selection(999)))
        writer = ProposalWriter()
        model = SimpleNamespace(with_structured_output=lambda schema: selector)
        with (
            patch("app.domains.analysis.proposal_writer.AnalysisSettings.from_env", return_value=settings()),
            patch("app.domains.analysis.proposal_writer.ChatOpenAI", return_value=model),
        ):
            assert (await writer.inspect(request())).status == "UNAVAILABLE"
        assert selector.ainvoke.await_count == 2
        assert not writer.template_cache
    asyncio.run(scenario())


def test_model_positive_without_valid_source_headings_is_an_inspection_failure():
    async def scenario():
        selector = SimpleNamespace(ainvoke=AsyncMock(return_value=selection()))
        writer = ProposalWriter()
        model = SimpleNamespace(with_structured_output=lambda schema: selector)
        with pytest.raises(ValueError):
            await writer._template_outline(model, request(), "test")
        assert not writer.template_cache
    asyncio.run(scenario())


def test_inspection_endpoint_contract_and_limits():
    from app.main import app
    from app.domains.analysis.proposal_writer import TemplateInspectResponse
    with patch("app.domains.analysis.api.proposal_writer.inspect", new_callable=AsyncMock) as inspect:
        inspect.return_value = TemplateInspectResponse(status="WRITABLE", section_titles=["사업 목표"])
        client = TestClient(app)
        data = {"fileName": "사업계획서(공고).hwp", "templateText": "사업 목표\n작성란"}
        result = client.post("/internal/monitoring/proposal-template", json=data)
        assert result.status_code == 200
        assert result.json()["sectionTitles"] == ["사업 목표"]
        assert client.post("/internal/monitoring/proposal-template", json={}).status_code == 422
        assert client.post("/internal/monitoring/proposal-template",
                           json=data | {"templateText": "가" * 80001}).status_code == 422


def test_inspection_prompt_separates_faq_answers_scalar_fields_and_mixed_forms():
    assert "파일명의 공고·FAQ·양식·신청서 같은 단어만으로" in OUTLINE_INSTRUCTIONS
    assert "FAQ·질의응답의 질문과 기관이 이미 제공한 답변" in OUTLINE_INSTRUCTIONS
    assert "뒤쪽에 실제 서술형 양식이 있으면" in OUTLINE_INSTRUCTIONS
    assert "예/아니오" in OUTLINE_INSTRUCTIONS


def test_mixed_form_document_titles_are_not_narrative_fields():
    from app.domains.analysis.proposal_outline import heading_candidates
    text = ("제출 서류 안내\n동의서\n성명\n운전자금 사업계획서\n"
            "3. 세부계획 및 소요자금\n필요한 자금의 용도를 상세히 서술\n"
            "시설자금 사업계획서\n1. 사업개요 (목적, 내용, 효과 등)\n"
            "설치 목표와 필요성을 작성합니다.")
    titles = {item["text"] for item in heading_candidates(text)}
    assert "운전자금 사업계획서" not in titles
    assert "시설자금 사업계획서" not in titles
    assert "3. 세부계획 및 소요자금" in titles
    assert "1. 사업개요 (목적, 내용, 효과 등)" in titles


def test_numbered_period_cell_is_not_a_prose_field_but_narrative_schedule_is_allowed():
    from app.domains.analysis.proposal_outline import heading_candidates
    text = "2. 사업기간 :\n3. 세부계획 및 소요자금\n자금 용도를 상세히 서술"
    assert "2. 사업기간 :" not in {item["text"] for item in heading_candidates(text)}
    text = "2. 사업기간 :\n추진 단계별 일정과 방법을 구체적으로 작성합니다."
    assert heading_candidates(text)[0]["text"] == "2. 사업기간 :"
