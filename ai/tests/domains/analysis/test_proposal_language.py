import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from app.domains.analysis.proposal_language import template_writing_language, verify_english_body
from app.domains.analysis.proposal_writer import (
    WRITING_INSTRUCTIONS,
    ProposalWriter,
    ProposalWriteRequest,
    TemplateSection,
    verify_writing,
    writing_instructions,
)

ENGLISH_BODY = (
    "Our company integrates manufacturing data and operates AI models for production teams. "
    "We will define the pilot scope with the customer and verify data access permissions before "
    "implementation. Our team will assess model performance against agreed criteria and document "
    "the findings for review. We will use the results to refine the operating process and plan "
    "subsequent deployment. Any budget or staffing commitments will require confirmation before "
    "we include them in the final implementation plan."
)
KOREAN_BODY = (
    "당사는 제조 현장 데이터를 통합하고 AI 모델을 운영하는 역량을 보유하고 있습니다. "
    "고객과 실증 범위를 정하고 데이터 접근 권한을 먼저 확인하겠습니다. "
    "합의한 기준에 따라 모델을 검증하고 결과를 담당자가 검토할 수 있도록 정리하겠습니다. "
    "검증 결과를 운영 과정에 반영하고 후속 적용 범위를 계획하겠습니다. "
    "예산과 인력은 확인된 가용 범위 안에서 배정하겠습니다. "
) * 3
ENGLISH_TEMPLATE = (
    "Executive Summary of Application (전체 1~2page 이내, 영문 작성)\n"
    "Business Area\n(영문 작성) 주요 사업 영역, 제품 및 서비스 등 기재\n"
    "Desired Field of Cooperation\n(영문 작성) 협력을 원하는 기술 및 산업 분야"
)
KOREAN_TEMPLATE = "사업계획서\n사업 목표\n제조 AI 실증 사업의 목표와 추진 방법을 작성합니다."


@pytest.mark.parametrize(
    "name,text,expected",
    [
        ("붙임 2. 신청서 영문요약 양식.hwpx", ENGLISH_TEMPLATE, "en"),
        ("신청서.hwpx", ENGLISH_TEMPLATE, "en"),
        ("Application (English).pdf", "Business Area\n주요 사업을 설명합니다.", "en"),
        ("양식(영어).hwp", "지원 목적\n당사 계획을 작성합니다.", "en"),
        ("양식.hwp", "본 양식은 영문으로 작성해 주세요.\nBusiness Area", "en"),
        ("template.pdf", "Please complete this form in English.\nProject objectives", "en"),
        ("template.pdf", "This form must be completed in English, not Korean.", "en"),
        ("양식.hwp", "본 양식은 영문으로 작성하고 한국어는 사용하지 않습니다.", "en"),
        (
            "template.pdf",
            "Project Objectives\nDescribe the implementation plan and expected outcomes.",
            "en",
        ),
        ("사업계획서.hwp", KOREAN_TEMPLATE, "ko"),
        ("국문 사업계획서.hwpx", "Project Objectives\nPlease write in English.", "ko"),
        ("English template.pdf", "This form must be completed in Korean.", "ko"),
        ("양식.hwp", "※ 국문으로 작성해 주세요.\nProject Objectives", "ko"),
        ("양식.hwp", "본 양식은 영문 작성이 필수가 아닙니다.\n사업 계획", "ko"),
        ("template.pdf", "Do not write in English.\n사업 계획", "ko"),
        ("양식.hwp", "본 양식은 영어 또는 한국어로 작성할 수 있습니다.", "ko"),
        ("양식.hwp", "Please write in English or Korean.\n사업 계획", "ko"),
        ("양식.hwp", "사업 목표\n회사명은 영문으로 작성합니다.\n수행 방법을 작성합니다.", "ko"),
        ("양식.hwp", "Please write your company name in English.\n사업 목표를 작성합니다.", "ko"),
        ("양식.hwp", "사업계획서\n별첨 영문 요약서는 별도로 작성합니다.\n수행 목표", "ko"),
        ("영문 자료/사업계획서.hwpx", KOREAN_TEMPLATE, "ko"),
        ("영어교육 지원사업 신청서.hwpx", KOREAN_TEMPLATE, "ko"),
        ("기업 영문명 등록 양식.hwpx", "회사명과 사업 내용을 작성합니다.", "ko"),
        ("국문 및 영문 양식.hwpx", ENGLISH_TEMPLATE, "ko"),
        ("양식.hwp", "Project Overview\n사업 목표를 작성합니다.\nAI GPU API ROI R&D", "ko"),
        ("양식.hwp", "", "ko"),
    ],
)
def test_language_comes_from_selected_form_and_defaults_to_korean(name, text, expected):
    assert template_writing_language(name, text) == expected


def output(body=ENGLISH_BODY):
    return {
        "sections": [
            {
                "section_id": 1,
                "body": body,
                "company_evidence_ids": [1],
                "confirmation_items": ["실제 적용 범위를 확인합니다."],
            }
        ]
    }


def test_english_draft_preserves_source_and_company_evidence():
    section = TemplateSection(
        title="Business Area", source_quote="Business Area", selection_reason="핵심 항목입니다."
    )
    result = verify_writing(output(), [section], ["service: 제조 AI 운영"], "양식", True, "en")
    assert result.status == "COMPLETED"
    assert result.sections[0].body == ENGLISH_BODY
    assert result.sections[0].source_quote == "Business Area"
    assert result.sections[0].company_evidence == ["제조 AI 운영"]
    assert result.sections[0].confirmation_items == ["실제 적용 범위를 확인합니다."]


@pytest.mark.parametrize(
    "body",
    [
        KOREAN_BODY,
        ENGLISH_BODY + "\n당사는 실제 수행 범위를 확인하겠습니다.",
        ENGLISH_BODY.rstrip("."),
        ENGLISH_BODY + " Please describe your company.",
        ENGLISH_BODY + " You should fill in this section.",
        ENGLISH_BODY + " [Insert company name].",
    ],
)
def test_english_validation_rejects_wrong_language_unfinished_prose_and_instructions(body):
    with pytest.raises(ValueError):
        verify_english_body(body)


def test_english_validation_allows_abbreviations_decimals_and_short_complete_paragraphs():
    verify_english_body(
        "Our company will assess U.S. requirements, e.g. access permissions, with the customer. "
        "We will compare version 2.5 with the baseline provided by Example Inc.\n\nWe will comply."
    )


@pytest.mark.parametrize("language,body", [("ko", ENGLISH_BODY), ("en", KOREAN_BODY)])
def test_draft_language_mismatch_is_rejected(language, body):
    section = TemplateSection(
        title="사업 목표", source_quote="사업 목표", selection_reason="핵심 항목입니다."
    )
    with pytest.raises(ValueError):
        verify_writing(output(body), [section], ["service: 제조 AI 운영"], "양식", False, language)


@pytest.mark.parametrize("failure", ["evidence", "missing", "duplicate", "short"])
def test_english_keeps_existing_evidence_section_and_length_constraints(failure):
    value = output()
    if failure == "evidence":
        value["sections"][0]["company_evidence_ids"] = [99]
    elif failure == "missing":
        value["sections"] = []
    elif failure == "duplicate":
        value["sections"] *= 2
    else:
        value["sections"][0]["body"] = "Our company will comply."
    section = TemplateSection(
        title="Business Area", source_quote="Business Area", selection_reason="핵심 항목입니다."
    )
    with pytest.raises(ValueError):
        verify_writing(value, [section], ["service: 제조 AI 운영"], "양식", False, "en")


def test_korean_prompt_unchanged_and_english_prompt_has_no_korean_prose_requirement():
    assert writing_instructions("ko") == WRITING_INSTRUCTIONS
    english = writing_instructions("en")
    assert "한국어 제안서 본문을 씁니다" not in english
    assert "모든 문장은 합니다체" not in english
    assert "'당사'로 호칭" not in english
    assert "'추진하겠습니다'" not in english
    assert "professional English paragraphs" in english
    assert "한국어로 씁니다" in english  # Confirmation notes stay readable in the Korean UI.


@pytest.mark.parametrize(
    "language,retry", [("ko", False), ("en", False), ("ko", True), ("en", True)]
)
def test_generation_routes_language_without_extra_model_call_and_keeps_one_retry(language, retry):
    async def scenario():
        selection = AsyncMock()
        selection.ainvoke.return_value = {
            "is_writing_template": True,
            "sections": [{"heading_line_ids": [2], "selection_reason": "핵심 항목입니다."}],
        }
        writing = AsyncMock()
        good = output(ENGLISH_BODY if language == "en" else KOREAN_BODY)
        wrong = output(KOREAN_BODY if language == "en" else ENGLISH_BODY)
        writing.ainvoke.side_effect = [wrong, good] if retry else [good]
        model = SimpleNamespace(
            with_structured_output=lambda schema: (
                selection if schema.__name__ == "TemplateSelectionOutput" else writing
            )
        )
        request = ProposalWriteRequest(
            title="English Summary 관련 국내 지원사업",
            notice_text="영문 요약서를 별도로 제출합니다.",
            file_name="신청서.hwpx",  # Determine language from the selected body, not the notice.
            template_text=ENGLISH_TEMPLATE if language == "en" else KOREAN_TEMPLATE,
        )
        settings = SimpleNamespace(
            proposal_model_name="test", api_key="test", proposal_timeout_seconds=180
        )
        with patch("app.domains.analysis.proposal_writer.ChatOpenAI", return_value=model):
            result = await ProposalWriter()._compose(
                request,
                {"service": "제조 현장 데이터 통합과 AI 모델 운영 서비스를 제공합니다."},
                settings,
                "2026-09-09",
            )
        assert result.status == "COMPLETED"
        assert result.sections[0].body == good["sections"][0]["body"].strip()
        assert selection.ainvoke.await_count == 1
        assert writing.ainvoke.await_count == (2 if retry else 1)
        messages = writing.ainvoke.call_args.args[0]
        payload = json.loads(messages[1][1])
        assert payload["writing_language"] == language
        if language == "en":
            assert "'우리 회사는'" not in messages[0][1]
            assert "모든 문장은 합니다체" not in messages[0][1]
        else:
            assert WRITING_INSTRUCTIONS in messages[0][1]
        if retry:
            assert "영어" in messages[-1][1] if language == "en" else "합니다체" in messages[-1][1]

    asyncio.run(scenario())
