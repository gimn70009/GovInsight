from datetime import datetime

import pytest

from app.domains.report.brief import SubmissionBrief, fallback_brief
from app.domains.report.facts import SubmissionFacts, submission_facts
from app.domains.report.presentation import deadline_display_lines
from app.domains.report.schemas.request import ReportDocumentRequest, ReportJobRequest
from app.domains.report.template import TemplateReportGenerator, _document_block

PAIRED = "대회 공고/ 접수마감: 2026. 9. 30(수) / 2026. 10. 31(토) 24:00까지"
DUPLICATE = "대회 공고/ 접수마감: 2026.9.30(수) /2026. 10.31(토) 24:00까지"
EXPECTED = ["대회 공고일: 2026년 9월 30일(수)", "접수 마감: 2026년 10월 31일(토) 24:00까지"]


def notice() -> ReportDocumentRequest:
    return ReportDocumentRequest.model_validate({
        "detectionId": 1, "documentId": 1, "versionId": 1,
        "organizationName": "산업통상부", "boardName": "사업공고",
        "changeType": "UNCHANGED_DOCUMENT", "title": "AI 라이프 솔루션 챌린지 공고",
        "originalUrl": "https://example.org/notice", "summary": "대회 공고입니다.",
        "importance": "NORMAL", "contentText": "대회 참가 안내입니다.",
        "attachments": [
            {"fileName": "공고문.hwp", "downloadUrl": "https://example.org/hwp",
             "extractedText": PAIRED},
            {"fileName": "공고문.pdf", "downloadUrl": "https://example.org/pdf",
             "extractedText": DUPLICATE},
        ],
    })


def test_duplicate_notice_attachments_produce_one_deadline_before_selection() -> None:
    doc = notice()
    assert submission_facts(doc).deadline == PAIRED
    doc.attachments.append(doc.attachments[0].model_copy(update={
        "file_name": "해외 접수 공고문.pdf",
        "extracted_text": "해외 접수마감: 2026. 11. 1. 18:00까지",
    }))
    assert "해외 접수마감" in submission_facts(doc).deadline


@pytest.mark.parametrize("compact", [False, True])
@pytest.mark.parametrize("mode", ["direct", "fallback", "brief"])
def test_all_report_paths_split_roles_and_preserve_midnight(mode: str, compact: bool) -> None:
    doc = notice()
    brief = None
    if mode == "fallback":
        brief = fallback_brief(doc, "대체 안내")
    elif mode == "brief":
        brief = SubmissionBrief(SubmissionFacts("원문 확인 필요", PAIRED + " / " + DUPLICATE,
                                               "원문 확인 필요", "원문 확인 필요"), [])
    body = _document_block(doc, compact=compact, brief=brief)
    assert f"• 제출·의견 기한: {EXPECTED[0]}\n  ↳ {EXPECTED[1]}" in body
    assert body.count("2026년 9월 30일") == body.count("2026년 10월 31일") == 1
    assert "대회 공고/" not in body
    assert "접수 시작" not in body


def test_complete_report_uses_same_deadline_format() -> None:
    req = ReportJobRequest(run_id=1, requested_at=datetime(2026, 10, 1), total_source_count=1,
                           detected_document_count=1, warning_count=0, documents=[notice()])
    body = TemplateReportGenerator().generate(req).summary
    assert EXPECTED[0] in body and EXPECTED[1] in body


@pytest.mark.parametrize(("value", "expected"), [
    (PAIRED + " / " + DUPLICATE, EXPECTED),
    ("공고일/접수마감: 2026/9/30 / 2026/10/31 24:00까지",
     ["공고일: 2026년 9월 30일", "접수 마감: 2026년 10월 31일 24:00까지"]),
    ("국내 마감: 2026-10-31 16:00까지 (한국표준시) / 해외 마감: 2026/10/31 18:00까지 (현지시간)",
     ["국내 마감: 2026년 10월 31일 16:00까지 (한국표준시)",
      "해외 마감: 2026년 10월 31일 18:00까지 (현지시간)"]),
    ("1차 마감: 2026.10.31. / 2차 마감: 2026.11.30.",
     ["1차 마감: 2026년 10월 31일", "2차 마감: 2026년 11월 30일"]),
    ("접수기간: 2026.9.30. ~ 2026.10.31. 24:00까지",
     ["접수기간: 2026년 9월 30일 ~ 2026년 10월 31일 24:00까지"]),
    ("2026/9/30 / 2026/10/31", ["2026년 9월 30일", "2026년 10월 31일"]),
    ("마감: 2026.10.31.(토) 18:00까지 (우편 도착분/소인분 기준)",
     ["마감: 2026년 10월 31일(토) 18:00까지 (우편 도착분/소인분 기준)"]),
    ("마감: 2026.10.31. 18:00까지 (도착분 기준)\n마감: 2026/10/31 18:00까지 (소인분 기준)",
     ["마감: 2026년 10월 31일 18:00까지 (도착분 기준)",
      "마감: 2026년 10월 31일 18:00까지 (소인분 기준)"]),
    ("마감: 2026.10.31.\n마감: 2027.10.31.",
     ["마감: 2026년 10월 31일", "마감: 2027년 10월 31일"]),
    ("마감: 2026-10-31\n마감: 2026. 10. 31.", ["마감: 2026년 10월 31일"]),
    ("2026/10/31까지 https://example.org/2026/10/31",
     ["2026년 10월 31일까지 https://example.org/2026/10/31"]),
    ("2026.10.31(토)24:00까지", ["2026년 10월 31일(토) 24:00까지"]),
    ("상시 접수", ["상시 접수"]),
    ("추후 안내", ["추후 안내"]),
    ("2026.2.30.", ["2026.2.30."]),
    ("'27.1.28. 16:00까지", ["2027년 1월 28일 16:00까지"]),
    ("10.31.까지", ["10월 31일까지"]),
])
def test_deadline_format_preserves_roles_conditions_and_date_meanings(
    value: str, expected: list[str],
) -> None:
    assert deadline_display_lines(value) == expected
