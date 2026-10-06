from dataclasses import replace

import pytest

from app.domains.report.presentation import (
    korean_display,
    readable_source,
    submission_display_lines,
    unreadable_table,
)
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_checklist_reuse import brief, ready_request

BROKEN = (
    "□ 신청 자격 및 포상규모(안) : 산업통상부장관 표창 8점 "
    "포상대상 부문 훈격 규모 수공기간 산업혁신인재성지원에 기여한 전문기관 종사자 "
    "유공자 장관표창 2 3년 이상 산업혁신인재성지원에 기여한 "
    "연구개발기관(주관·공동) 종사자 6 계 8"
)


def test_screenshot_table_is_rejected_in_validation_and_fallback():
    assert unreadable_table(BROKEN)
    assert readable_source(BROKEN, "applicant") is None
    assert korean_display(BROKEN, BROKEN, "전문기관 및 연구개발기관 종사자",
                          kind="applicant") is None
    assert submission_display_lines(BROKEN, "제출 주체·대상") == [
        "원문 표에서 신청 자격과 제외 조건을 확인해 주세요.",
    ]


@pytest.mark.parametrize("label", [
    "제출 주체·대상", "제출·의견 기한", "제출처·방법", "문의 담당", "사업 요약",
])
def test_final_quality_gate_is_shared_by_all_fields(label):
    lines = submission_display_lines(BROKEN, label)
    assert len(lines) == 1
    assert "원문 표에서" in lines[0]
    assert "훈격" not in lines[0] and "6 계 8" not in lines[0]


def test_verified_report_cannot_bypass_final_quality_gate():
    request = ready_request()
    document = request.documents[0]
    document.summary = BROKEN
    result = brief(request)
    result = replace(result, facts=replace(result.facts, applicant=BROKEN))
    body = TemplateReportGenerator().generate(
        request, briefs={document.detection_id: result},
    ).summary
    assert "원문 표에서 신청 자격과 제외 조건을 확인해 주세요." in body
    assert "원문 표에서 사업 내용과 세부 조건을 확인해 주세요." in body
    assert "6 계 8" not in body and "훈격" not in body


def test_square_bullets_and_sentences_are_readable_without_losing_conditions():
    assert submission_display_lines(
        "□ 신청 자격: 중소기업 ■ 업력 3년 이상. 단, 참여제한 기관은 제외합니다.",
        "제출 주체·대상",
    ) == ["중소기업", "업력 3년 이상. 단, 참여제한 기관은 제외합니다."]
    assert submission_display_lines(
        "접수는 온라인으로 진행합니다. 우편은 접수하지 않습니다.", "제출처·방법",
    ) == ["접수는 온라인으로 진행합니다.", "우편은 접수하지 않습니다."]


@pytest.mark.parametrize("value", [
    "포상규모: 8점. 수공기간: 3년 이상.",
    "사업비 3.5억원, 인원 8명, 업력 3년 이상인 기업",
    "2026년 10월 14일 18:00까지. 단, 도착분만 유효합니다.",
    "02-1234-5678 / help@example.org / https://example.org/a.b?x=3.5",
    "지원대상은 중소기업이며 지원규모와 지원기간은 원문을 참조합니다.",
])
def test_readable_numbers_and_conditions_are_not_mistaken_for_broken_tables(value):
    assert not unreadable_table(value)
