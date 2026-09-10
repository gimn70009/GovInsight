import asyncio
import json
import logging
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from pydantic import ValidationError

from app.domains.analysis.proposal_outline import heading_candidates
from app.domains.analysis.proposal_writer import (
    ProposalWriter,
    ProposalWriteRequest,
    TemplateSelectionOutput,
    WrittenProposal,
    normalize_draft_body,
    validation_hint,
    verify_writing,
)
from tests.domains.analysis.test_proposal_language import ENGLISH_BODY

TEMPLATE = (Path(__file__).parent / "fixtures/english_summary_table.txt").read_text(
    encoding="utf-8"
)
REQUEST = ProposalWriteRequest(
    title="해외 협력 지원 공고",
    notice_text="해외 협력을 지원합니다.",
    file_name="신청서 영문요약.hwpx",
    template_text=TEMPLATE,
)
PROFILE = {"service": "제조 현장의 데이터 통합과 AI 모델 운영 서비스를 제공합니다."}
SETTINGS = SimpleNamespace(proposal_model_name="test", api_key="test", proposal_timeout_seconds=180)


def selection(*ids, is_template=True):
    return {
        "is_writing_template": is_template,
        "sections": [
            {
                "heading_line_ids": [index],
                "selection_reason": "실제 사업 내용을 작성하는 항목입니다.",
            }
            for index in ids
        ],
    }


def written(body=ENGLISH_BODY):
    return {
        "sections": [
            {
                "section_id": index,
                "body": body,
                "company_evidence_ids": [1],
                "confirmation_items": ["실제 협력 범위를 확인합니다."],
            }
            for index in (1, 2)
        ]
    }


async def compose(selections, bodies):
    outline = SimpleNamespace(ainvoke=AsyncMock(side_effect=selections))
    writer = SimpleNamespace(ainvoke=AsyncMock(side_effect=bodies))
    model = SimpleNamespace(
        with_structured_output=lambda schema: (
            outline if schema == TemplateSelectionOutput else writer
        )
    )
    with patch("app.domains.analysis.proposal_writer.ChatOpenAI", return_value=model):
        result = await ProposalWriter()._compose(REQUEST, PROFILE, SETTINGS, "2026-09-10")
    return result, outline, writer


def test_real_table_keeps_two_narrative_fields_with_original_line_ids_and_guidance():
    result = heading_candidates(TEMPLATE)
    assert [(item["line"], item["text"]) for item in result] == [
        (30, "Business Area"),
        (32, "Desired Field of Cooperation"),
    ]
    assert all(item["has_writing_guidance"] for item in result)
    assert "판매현황" in result[0]["context"]
    assert "협력을 원하는" in result[1]["context"]
    assert "Company nameAddress" not in json.dumps(result)


def test_reproduced_aggregate_and_document_title_selections_keep_valid_fields(caplog):
    result, outline, writer = asyncio.run(compose([selection(1, 3, 30, 32)], [written()]))
    assert result.status == "COMPLETED"
    assert [section.title for section in result.sections] == [
        "Business Area",
        "Desired Field of Cooperation",
    ]
    assert "4개보다 적어" in result.message
    assert outline.ainvoke.await_count == writer.ainvoke.await_count == 1
    assert all(section.source_quote in TEMPLATE for section in result.sections)
    assert ENGLISH_BODY not in caplog.text


@pytest.mark.parametrize("first", [selection(999), selection(1, 3)])
def test_invalid_or_missed_outline_is_reselected_once_before_body(first):
    result, outline, writer = asyncio.run(compose([first, selection(30, 32)], [written()]))
    assert result.status == "COMPLETED"
    assert outline.ainvoke.await_count == 2
    assert writer.ainvoke.await_count == 1
    messages = outline.ainvoke.call_args.args[0]
    assert messages[-2][0] == "assistant"
    assert "수정 대상 데이터" in messages[-1][1]


def test_non_template_decision_is_not_retried_into_completed():
    result, outline, writer = asyncio.run(
        compose([selection(is_template=False)], [])
    )
    assert result.status == "NEEDS_TEMPLATE"
    assert outline.ainvoke.await_count == 1
    assert writer.ainvoke.await_count == 0


def test_outline_repair_and_invalid_body_never_exceed_three_total_model_calls():
    async def scenario():
        outline = AsyncMock(side_effect=[selection(999), selection(30, 32)])
        body = AsyncMock(return_value=written(ENGLISH_BODY + " We will develop and"))
        model = SimpleNamespace(
            with_structured_output=lambda schema: SimpleNamespace(
                ainvoke=outline if schema == TemplateSelectionOutput else body
            )
        )
        writer = ProposalWriter()
        with (
            patch("app.domains.analysis.proposal_writer.ChatOpenAI", return_value=model),
            patch(
                "app.domains.analysis.proposal_writer.AnalysisSettings.from_env",
                return_value=SETTINGS,
            ),
        ):
            result = await writer.write(REQUEST)
        assert result.status == "UNAVAILABLE"
        assert not writer.cache
        assert outline.await_count == 2
        assert body.await_count == 1

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "note",
    [
        " (company_evidence_ids: [1,2,3,11,12,13,14,15])",
        " (evidence_ids: 1, 2)",
    ],
)
def test_numeric_evidence_metadata_is_removed_without_discarding_english_prose(note):
    result, outline, writer = asyncio.run(
        compose([selection(30, 32)], [written(ENGLISH_BODY + note)])
    )
    assert result.status == "COMPLETED"
    assert all(section.body == ENGLISH_BODY for section in result.sections)
    assert all(section.company_evidence for section in result.sections)
    assert outline.ainvoke.await_count == writer.ainvoke.await_count == 1


def test_normalization_preserves_technical_parentheses_and_non_numeric_content():
    body = ENGLISH_BODY + " We use predictive maintenance (PdM)."
    assert normalize_draft_body(body) == body
    assert "needs confirmation" in normalize_draft_body(
        ENGLISH_BODY + " (evidence_ids: needs confirmation)"
    )


def test_metadata_cannot_pad_a_too_short_body_past_the_minimum():
    from app.domains.analysis.proposal_writer import selected_outline

    outline = selected_outline(selection(30, 32), TEMPLATE)
    body = "Our company will comply." + " (company_evidence_ids: [1,2,3])" * 20
    with pytest.raises(ValueError, match="400~2,600"):
        verify_writing(written(body), outline, ["service: 제조 AI 운영"], "양식", False, "en")


def test_schema_error_feedback_and_logs_omit_model_input(caplog):
    secret = "PRIVATE_COMPANY_TEXT"
    with pytest.raises(ValidationError) as failure:
        WrittenProposal.model_validate(written(secret))
    hint = validation_hint(failure.value)
    assert "sections.0.body" in hint
    assert "string_too_short" in hint
    assert secret not in hint
    assert secret not in validation_hint(ValueError(secret))
    with patch.object(logging.getLogger("app"), "propagate", True):
        result, _, writer = asyncio.run(compose([selection(30, 32)], [failure.value, written()]))
    assert result.status == "COMPLETED"
    assert writer.ainvoke.await_count == 2
    assert "stage=body" in caplog.text
    assert "string_too_short" in caplog.text
    assert secret not in caplog.text


def test_body_repair_receives_the_rejected_answer_and_preserves_semantic_guards():
    bad = written()
    bad["sections"][0]["company_evidence_ids"] = [999]
    result, _, writer = asyncio.run(compose([selection(30, 32)], [bad, written()]))
    assert result.status == "COMPLETED"
    messages = writer.ainvoke.call_args.args[0]
    assert json.loads(messages[-2][1]) == bad
    assert "제공된 회사 정보만" in messages[-1][1]


def test_generic_korean_and_multiline_candidates_preserve_order_and_typos():
    text = (
        "다. 실증의 위한 구역, 기간, 규모\n기간을 기술합니다.\n"
        "사업실시(실증)\n항목 및 내용\n계획을 작성합니다."
    )
    result = heading_candidates(text)
    assert [(item["line"], item["text"]) for item in result] == [
        (1, "다. 실증의 위한 구역, 기간, 규모"),
        (3, "사업실시(실증)"),
        (4, "항목 및 내용"),
    ]


@pytest.mark.parametrize("title", ["Revenue", "R&D"])
def test_explicit_narrative_instructions_preserve_other_forms_metric_sections(title):
    text = title + "\nDescribe the business model, implementation plan and expected results."
    assert heading_candidates(text)[0]["text"] == title
