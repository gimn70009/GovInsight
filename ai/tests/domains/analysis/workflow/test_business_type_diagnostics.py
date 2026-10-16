import logging
from pathlib import Path

import pytest

from app.domains.analysis.schemas.request import AnalysisAttachmentRequest
from app.domains.analysis.workflow.business_type import (
    diagnose_business_type,
    validated_business_type,
)
from tests.domains.analysis.workflow.test_business_type import TEXT, assessment
from tests.domains.analysis.workflow.test_graph import document


@pytest.mark.parametrize("heading", [
    "붙임 1 공고문", "[붙임 2] 참여기업 모집공고", "별첨 제3호 사업 안내문",
])
def test_numbered_notice_is_retained_and_later_form_is_still_excluded(heading):
    text = heading + "\n" + TEXT + "\n별첨 2 신청서\n원문이 아닌 연구개발 예시입니다."
    current = document().model_copy(update={"content_text": text})
    result = diagnose_business_type(assessment(), current)
    assert result.reason == "VALIDATED"
    assert result.assessment.sources[0].excerpt == TEXT
    example = assessment(sources=[
        dict(origin="NOTICE_BODY", excerpt="원문이 아닌 연구개발 예시입니다."),
    ])
    rejected = diagnose_business_type(example, current)
    assert rejected.reason == "EXCLUDED_SOURCE_SECTION"
    assert rejected.assessment is None


@pytest.mark.parametrize("heading", [
    "붙임 1 공고문 작성 예시", "서식 1 공고문", "별첨 1 신청서 (모집공고 참고)",
    "붙임 1", "별지 2 사업계획서", "작성 예시 모집공고",
])
def test_forms_and_ambiguous_appendices_are_not_promoted_to_notices(heading):
    current = document().model_copy(update={"content_text": heading + "\n" + TEXT})
    assert diagnose_business_type(assessment(), current).reason == "EXCLUDED_SOURCE_SECTION"


def test_actual_public_quote_with_numbered_notice_heading_survives():
    path = Path(__file__).resolve().parents[5] / (
        "backend/src/test/resources/participation-conditions/certification-hwpx.txt"
    )
    text = path.read_text(encoding="utf-8")
    quote = next(line for line in text.splitlines() if line.startswith("□ 지원내용"))
    value = assessment(primaryType="CERTIFICATION", sources=[dict(
        origin="ATTACHMENT", attachmentName="공고문.hwpx", excerpt=quote,
    )])
    file = AnalysisAttachmentRequest(
        attachment_id=1, file_name="공고문.hwpx", extracted_text="붙임 1 공고문\n" + text,
    )
    current = document().model_copy(update={"attachments": [file]})
    result = diagnose_business_type(value, current)
    assert result.assessment == value
    assert result.reason == "VALIDATED"


@pytest.mark.parametrize("name,text,reference,copies,reason", [
    ("공고.pdf", TEXT, "다른공고.pdf", 1, "SOURCE_NOT_FOUND"),
    ("공고.pdf", TEXT, "공고.pdf", 2, "AMBIGUOUS_SOURCE"),
    ("공고.pdf", None, "공고.pdf", 1, "SOURCE_TEXT_UNAVAILABLE"),
    ("신청서.pdf", TEXT, "신청서.pdf", 1, "EXCLUDED_SOURCE"),
    ("공고.pdf", "별첨 1 신청서\n" + TEXT, "공고.pdf", 1, "EXCLUDED_SOURCE_SECTION"),
    ("공고.pdf", "다른 지원 내용입니다.", "공고.pdf", 1, "QUOTE_MISMATCH"),
])
def test_rejection_reasons_distinguish_source_and_quote_failures(
    name, text, reference, copies, reason,
):
    file = AnalysisAttachmentRequest(attachment_id=1, file_name=name, extracted_text=text)
    current = document().model_copy(update={"attachments": [file] * copies})
    value = assessment(sources=[dict(origin="ATTACHMENT", attachmentName=reference, excerpt=TEXT)])
    result = diagnose_business_type(value, current)
    assert result.reason == reason
    assert result.source_index == 0
    assert result.assessment is None


def test_no_model_classification_does_not_claim_a_model_error_or_general_notice():
    assert diagnose_business_type(None, document()).reason == "MODEL_UNCLASSIFIED"
    excluded = document().model_copy(update={"content_text": "별첨 1 신청서\n" + TEXT})
    assert diagnose_business_type(None, excluded).reason == "NO_USABLE_SOURCE"


def test_future_and_negative_support_remain_rejected():
    for quote in ["향후 설비 도입으로 연계 가능합니다.", "연구개발 지원 불가 안내입니다."]:
        value = assessment(sources=[dict(origin="NOTICE_BODY", excerpt=quote)])
        current = document().model_copy(update={"content_text": quote})
        assert diagnose_business_type(value, current).reason == "UNSUPPORTED_EVIDENCE"


def test_all_quotes_must_pass_and_failure_index_is_reported():
    value = assessment(sources=[
        dict(origin="NOTICE_BODY", excerpt=TEXT),
        dict(origin="NOTICE_BODY", excerpt="원문에 없는 근거 문장입니다."),
    ])
    result = diagnose_business_type(value, document().model_copy(update={"content_text": TEXT}))
    assert result.reason == "QUOTE_MISMATCH"
    assert result.source_index == 1
    assert result.assessment is None


def test_logging_correlates_ids_without_source_text_or_file_names(caplog):
    name = "외부에노출하면안되는파일명.pdf"
    value = assessment(sources=[dict(origin="ATTACHMENT", attachmentName=name, excerpt=TEXT)])
    current = document().model_copy(update={"attachments": [AnalysisAttachmentRequest(
        attachment_id=1, file_name=name, extracted_text=None,
    )]})
    logger = logging.getLogger("app.domains.analysis.workflow.business_type")
    # Application logging may disable propagation before this test in the full suite.
    logger.addHandler(caplog.handler)
    try:
        with caplog.at_level(logging.INFO, logger=logger.name):
            assert validated_business_type(value, current) is None
    finally:
        logger.removeHandler(caplog.handler)
    assert "detection_id=10 version_id=30 reason=SOURCE_TEXT_UNAVAILABLE" in caplog.text
    assert "empty_attachment_count=1" in caplog.text
    assert name not in caplog.text
    assert TEXT not in caplog.text
