"""Quality warnings compare explicit schedule clauses without generating dates."""

import pytest

from app.domains.report.quality import schedule_consistency_issues
from tests.domains.report.test_report_template import with_document


def issues(summary, actual):
    return schedule_consistency_issues(with_document(summary=summary).documents[0], actual)


@pytest.mark.parametrize("other", [
    "공고일은 2026년 10월 6일", "사업기간은 2026년 11월 1일부터 2027년 10월 31일",
    "접수 시작일은 2026년 10월 1일", "시상일은 2026년 12월 1일",
])
def test_later_nonclosing_schedule_does_not_replace_the_claimed_deadline(other):
    assert issues("신청 마감은 2026년 10월 14일이며 " + other + "입니다.",
                  "접수 마감: 2026년 10월 14일") == ()


@pytest.mark.parametrize(("left", "right"), [
    ("국내", "해외"), ("국문", "영문"), ("한국", "스페인"), ("1차", "2차"),
])
def test_swapped_country_language_or_round_deadlines_are_conflicts(left, right):
    summary = (f"{left} 신청마감은 2026년 10월 30일, "
               f"{right} 신청마감은 2026년 10월 14일입니다.")
    actual = (f"{left} 신청마감: 2026년 10월 14일\n"
              f"{right} 신청마감: 2026년 10월 30일")
    assert issues(summary, actual) == ("deadline:summary_conflict",)


def test_matching_country_endpoints_can_appear_in_a_different_order():
    summary = "국내 신청마감은 2026년 10월 14일, 해외 신청마감은 2026년 10월 30일입니다."
    actual = "해외 신청마감: 2026년 10월 30일\n국내 신청마감: 2026년 10월 14일"
    assert issues(summary, actual) == ()


@pytest.mark.parametrize(("summary", "actual"), [
    ("신청마감은 2026년 10월 20일입니다.",
     "국내 신청마감: 2026년 10월 14일\n해외 신청마감: 2026년 10월 30일"),
    ("국내 신청마감은 2026년 10월 20일입니다.", "접수 마감: 2026년 10월 14일"),
    ("신청 마감은 2026년 10월 14일 또는 2026년 10월 30일입니다.",
     "접수 마감: 2026년 10월 20일"),
])
def test_ambiguous_role_or_endpoint_does_not_assert_a_conflict(summary, actual):
    assert issues(summary, actual) == ()


def test_each_role_compares_its_range_end_instead_of_the_shared_start():
    summary = ("국내 신청기간은 2026년 10월 1일부터 2026년 10월 14일까지, "
               "해외 신청기간은 2026년 10월 1일부터 2026년 10월 30일까지입니다.")
    actual = "국내 접수마감: 2026년 10월 30일\n해외 접수마감: 2026년 10월 14일"
    assert issues(summary, actual) == ("deadline:summary_conflict",)
