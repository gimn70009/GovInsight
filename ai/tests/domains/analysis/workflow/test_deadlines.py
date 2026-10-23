from datetime import date

import pytest

from app.domains.analysis.workflow.deadlines import application_deadline


@pytest.mark.parametrize("text", [
    "2026-10-30",
    "2026년 10월 30일 18시",
    "2026. 9. 1.(화) 09:00 ~ 2026. 10. 30.(금) 18:00",
    "2026-09-01부터 2026년 10월 30일까지",
    "2026년 9월 1일부터 2026-10-30까지",
    "~2026-10-30까지",
])
def test_deadline_field_selects_complete_end_date(text: str) -> None:
    assert application_deadline(text) == date(2026, 10, 30)


@pytest.mark.parametrize("text", [
    "분석일은 2026-10-08이며 신청 마감일은 2026-10-30입니다.",
    "신청 마감일은 2026년 10월 30일이며 2026년 10월 8일 분석 기준입니다.",
    "신청 기간은 2026-09-01부터 2026-10-30까지입니다.",
    "접수기간: 2026년 9월 1일 ~ 2026-10-30",
    "제출 기한은 2026-10-30이며 발표일은 2026-11-05입니다.",
    "신청 마감일: 2026-10-30, 접수 마감일: 2026-10-30",
])
def test_labelled_deadline_is_independent_of_date_format_and_other_date_roles(text: str) -> None:
    assert application_deadline(text, require_label=True) == date(2026, 10, 30)


@pytest.mark.parametrize("text", [
    "2026-09-01부터 10월 30일까지",
    "2026-09-01 ~ 10.30.",
    "2026-10-30부터",
    "2026-10-30 ~",
    "2026-10-30부터 2026-09-01까지",
    "2026-09-01 또는 2026-10-30",
    "2026-09-01 / 2026-10-30",
    "2026-09-01 ~ 2026-10-30, 2026-11-01",
    "2026-02-30 ~ 2026-10-30",
    "2026-09-01 ~ 2026-10-32",
    "신청 마감일 2026-10-30 또는 11월 2일",
    "신청 마감일 2026-10-30, 제출 마감일 2026-11-02",
    "분석일 2026-09-01",
    "공고일 2026-09-01",
    "접수 시작일 2026-09-01",
    "행사일 2026-09-01",
    "사업기간 2026-09-01 ~ 2026-10-30",
    "평가 마감일은 2026-09-01입니다.",
    "공고 마감일은 2026-09-01입니다.",
    "접수기간: 2026-09-01 -",
    "접수기간: 2026-09-01 –",
    "접수기간: 2026-09-01 to",
    "접수기간: 2026-09-01 시작, 종료일 미정",
    "신청기간: 2026-09-01 개시, 마감일 미정",
    "접수기간: 2026-09-01 이후 상시 접수",
    "신청 마감일은 2026-09-01이며 공고 변경으로 2026-10-30까지 연장되었습니다.",
    "신청 마감일은 2026-09-01이며 공고문에는 2026-10-30으로 기재되어 있습니다.",
])
def test_ambiguous_or_non_deadline_dates_remain_unknown(text: str) -> None:
    assert application_deadline(text) is None
    assert application_deadline(text, require_label=True) is None


@pytest.mark.parametrize(("original", "expected"), [
    ("2026-10-29 (목) 23:59:00, 시스템 접수 기준으로 마감되었습니다.",
     "2026-10-29 (목) 23:59:00, 시스템 접수 기준 마감입니다."),
    ("접수 마감: 2026.10.29.(목) 18:00 마감됐습니다.",
     "접수 마감: 2026.10.29.(목) 18:00 마감입니다."),
    ("2026년 10월 29일 18시 30분까지 종료되었습니다.",
     "2026년 10월 29일 18시 30분까지입니다."),
    ("2025-01-01 마감되었습니다.", "2025-01-01 마감입니다."),
])
def test_fixed_deadline_preserves_calendar_and_receipt_facts_without_stale_status(
    original, expected,
):
    from app.domains.analysis.workflow.deadlines import normalize_deadline_presentation

    assert normalize_deadline_presentation(original) == expected
    assert normalize_deadline_presentation(expected) == expected
    assert application_deadline(expected) == application_deadline(original)


@pytest.mark.parametrize("text", [
    "", "기한 미확인", "2026-10-29 18:00", "2026-02-30 마감되었습니다.",
    "2026-10-29 25:00 마감되었습니다.",
    "2026-10-29 예정이었으나 예산 소진으로 조기 마감되었습니다.",
    "2026-10-29 접수가 취소되었습니다.",
    "모집 정원 충족으로 2026-10-29 전에 마감되었습니다.",
    "2026-10-29까지 접수하되 예산 소진 시 마감됩니다.",
    "국내 2026-10-29 마감되었습니다. 해외 2026-11-02까지 접수합니다.",
    "2026-09-01부터 2026-10-29까지 마감되었습니다.",
    "기존 접수는 2026-10-29 마감되었습니다. 추가 접수는 별도 안내합니다.",
    "행사일 2026-10-29 마감되었습니다.",
    "2026-10-29 우편 소인분까지 마감되었습니다.",
])
def test_deadline_presentation_preserves_exceptions_multiple_dates_and_uncertainty(text):
    from app.domains.analysis.workflow.deadlines import normalize_deadline_presentation

    assert normalize_deadline_presentation(text) == text
