import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError

from app.domains.analysis.schemas.business_type import BusinessTypeAssessment, BusinessTypeCode
from app.domains.analysis.schemas.request import AnalysisAttachmentRequest
from app.domains.analysis.schemas.result import Favorability
from app.domains.analysis.workflow.agent import LangChainAnalysisRunner
from app.domains.analysis.workflow.business_type import validated_business_type
from tests.domains.analysis.evidence.fakes import collect_all_evidence
from tests.domains.analysis.workflow.test_graph import analysis, document

TEXT = "유형1은 배출량 관리 시스템 구축을, 유형2는 제3자 검증 비용을 지원합니다."


def assessment(**updates):
    payload = dict(
        primaryType="ADOPTION",
        explanation="배출량 관리 시스템 도입을 지원합니다.",
        details=["유형2는 외부 검증 비용을 지원합니다."],
        sources=[dict(origin="NOTICE_BODY", attachmentName=None, excerpt=TEXT)],
    )
    payload.update(updates)
    return BusinessTypeAssessment.model_validate(payload)


@pytest.mark.parametrize("code", list(BusinessTypeCode))
def test_ten_fixed_codes_serialize_for_backend(code):
    result = assessment(primaryType=code)
    assert result.model_dump(mode="json", by_alias=True)["primaryType"] == code.value


def test_unapproved_category_is_not_accepted():
    with pytest.raises(ValidationError):
        assessment(primaryType="창업지원")


def test_current_quote_survives_but_previous_or_fabricated_quote_does_not():
    current = document().model_copy(update={"content_text": TEXT})
    assert validated_business_type(assessment(), current) is not None
    assert validated_business_type(assessment(), document("UPDATED_DOCUMENT")) is None
    wrong = assessment(
        sources=[dict(origin="NOTICE_BODY", excerpt="원문에 없는 연구개발을 지원합니다.")]
    )
    assert validated_business_type(wrong, current) is None


def test_attachment_name_must_be_unambiguous_and_exact():
    source = assessment(
        sources=[dict(origin="ATTACHMENT", attachmentName="공고.pdf", excerpt=TEXT)]
    )
    file = AnalysisAttachmentRequest(attachment_id=1, file_name="공고.pdf", extracted_text=TEXT)
    current = document().model_copy(update={"attachments": [file]})
    assert validated_business_type(source, current) is not None
    assert (
        validated_business_type(source, current.model_copy(update={"attachments": [file, file]}))
        is None
    )
    renamed = file.model_copy(update={"file_name": "다른공고.pdf"})
    assert (
        validated_business_type(source, current.model_copy(update={"attachments": [renamed]}))
        is None
    )


def test_form_example_and_future_support_are_not_current_support():
    current = document().model_copy(update={"content_text": "별첨 1 사업계획서\n" + TEXT})
    assert validated_business_type(assessment(), current) is None
    future = "향후 시스템 도입 지원으로 연계 가능합니다."
    value = assessment(sources=[dict(origin="NOTICE_BODY", excerpt=future)])
    assert (
        validated_business_type(value, document().model_copy(update={"content_text": future}))
        is None
    )


@pytest.mark.parametrize("grounded", [True, False])
def test_existing_analysis_call_stores_type_or_drops_invalid_quote_without_failing(grounded):
    runner = LangChainAnalysisRunner.__new__(LangChainAnalysisRunner)
    runner._settings = SimpleNamespace(max_text_chars=20_000, timeout_seconds=5, model_name="mock")
    runner._evidence_agent = SimpleNamespace(collect=collect_all_evidence)
    draft = analysis(Favorability.NOT_APPLICABLE).draft
    draft.comparison_summary.business_type = assessment()
    runner._analysis_model = AsyncMock()
    runner._analysis_model.ainvoke.return_value = draft.model_dump()
    runner._assess_legal_risks = AsyncMock(return_value=draft.comparison_summary.legal_risks)
    current = document().model_copy(update={"content_text": TEXT}) if grounded else document()
    result = asyncio.run(runner.analyze(current)).draft
    runner._analysis_model.ainvoke.assert_awaited_once()
    assert (
        "CERTIFICATION=시험·인증" in runner._analysis_model.ainvoke.call_args.args[0][0]["content"]
    )
    value = result.comparison_summary.model_dump(mode="json", by_alias=True)["businessType"]
    assert (value is not None) == grounded
    if grounded:
        assert value["primaryType"] == "ADOPTION"
        assert value["sources"][0]["excerpt"] == TEXT


def test_archive_examples_do_not_ground_classification():
    text = "[파일: 작성예시.pdf]\n" + TEXT + "\n[파일: 모집공고.pdf]\n입주 공간을 제공합니다."
    current = document().model_copy(update={"content_text": text})
    assert validated_business_type(assessment(), current) is None
    valid = assessment(
        primaryType="SPACE", sources=[dict(origin="NOTICE_BODY", excerpt="입주 공간을 제공합니다.")]
    )
    assert validated_business_type(valid, current) is not None
