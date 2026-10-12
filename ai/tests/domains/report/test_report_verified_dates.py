"""Public date-only fixtures reduced from the three reported notices."""

import pytest

from app.domains.report.fact_reuse import verified_submission_facts
from app.domains.report.facts import source_schedule_rows, submission_facts
from app.domains.report.presentation import informational_notice, korean_display
from app.domains.report.schemas.request import ReportDocumentRequest

JAPAN = (
    "영문 연구개발계획서 접수 마감\n< ’27.1.20(수) >\n(접수처) 기관 홈페이지\n"
    "국문 연구개발계획서 접수 마감\n< ’27.1.21(목) 16:00(KST) >\n(접수처) 기관 홈페이지"
)
AWARD = (
    "□ 신청기간 : 2026. 10. 7.(수) ∼ 2026. 10. 26.(월)\n□ 신청서류\n"
    "ㅇ 제출서류는 우편(마감일 소인분까지 유효) 접수 후 이메일 제출\n"
    "* 방문접수는 마감일 18시까지 접수함"
)
ENERGY = (
    "공고기간\n’26.10.12.(월) ~ 11.10.(화) 까지\n"
    "신청기간\n(접수마감)\n’26.10.19.(월) ~ 11.10.(화) 18:00 까지\n"
    "* 접수마감시간 엄수"
)


def document(source, *, saved=None, attachment=None):
    return ReportDocumentRequest(
        detection_id=1, document_id=1, version_id=1, organization_name="공공기관",
        board_name="공고", change_type="NEW_DOCUMENT", title="공개 신청 공고",
        original_url="https://example.org/notice", summary="신청 공고입니다.", importance="NORMAL",
        content_text=source, comparison_summary={"applicationDeadline": saved},
        attachments=([{"fileName": "공고문.hwpx", "downloadUrl": "https://example.org/notice.hwpx",
                       "extractedText": attachment}] if attachment else []),
    )


def test_no_colon_heading_and_bracketed_date_keep_language_and_timezone():
    assert source_schedule_rows(JAPAN) == [
        "영문 연구개발계획서 접수 마감: 27.1.20(수)",
        "국문 연구개발계획서 접수 마감: 27.1.21(목) 16:00(KST)",
    ]
    assert "16:00(KST)" in submission_facts(document(JAPAN), source_only=True).deadline


def test_parenthesized_deadline_subheading_binds_to_application_period():
    assert source_schedule_rows(ENERGY) == [
        "신청기간: 26.10.19.(월) ~ 11.10.(화) 18:00 까지",
    ]


def test_source_recovery_preserves_postmark_and_visit_cutoff():
    value = submission_facts(document(AWARD), source_only=True).deadline
    assert "2026. 10. 26.(월)" in value
    assert "우편(마감일 소인분까지 유효)" in value
    assert "방문접수는 마감일 18시까지 접수함" in value


def test_flattened_long_candidate_does_not_block_the_same_verified_attachment_period():
    flat = AWARD.replace("\n", " ") + " □ 신청서류 " + "제출 양식 설명 " * 180
    saved = "2026.10.26 (우편 소인분 및 방문 접수 기준, 방문은 마감일 18시까지)"
    reused = verified_submission_facts(document(flat, saved=saved, attachment=AWARD))
    assert "deadline" in reused
    assert "소인" in reused["deadline"] and "18시" in reused["deadline"]


@pytest.mark.parametrize("other", [
    "접수기간: 2026.11.7 ~ 2026.11.26",
    "국외 접수기간: 2026.10.7 ~ 2026.10.26",
])
def test_different_period_or_country_is_not_silently_overridden(other):
    doc = document(AWARD, saved="2026.10.26", attachment=other)
    assert "deadline" not in verified_submission_facts(doc)


def test_reuse_does_not_drop_a_saved_postmark_rule_missing_from_source():
    doc = document("접수기간: 2026.10.7 ~ 2026.10.26",
                   saved="2026.10.26 우편 소인분 기준")
    assert "deadline" not in verified_submission_facts(doc)


@pytest.mark.parametrize(("quote", "display", "expected"), [
    ("신청기간: 2026.10.7부터 2026.10.26까지",
     "2026년 10월 7일부터 2026년 10월 26일까지",
     "신청기간: 2026년 10월 7일부터 2026년 10월 26일까지"),
    ("국문 연구개발계획서 접수 마감\n< ’27.1.21(목) 16:00(KST) >",
     "국문: 2027년 1월 21일(목) 16:00(KST)",
     "국문 연구개발계획서 접수 마감: 2027년 1월 21일(목) 16:00(KST)"),
    ("공고문 국문 접수 마감일: 2027-01-21 16:00 KST",
     "국문 접수 마감일: 2027년 1월 21일 16:00 KST",
     "국문 접수 마감일: 2027년 1월 21일 16:00 KST"),
])
def test_readable_date_gets_only_its_verified_source_role(quote, display, expected):
    assert korean_display(quote, quote, display, kind="deadline") == expected


@pytest.mark.parametrize(("quote", "display"), [
    ("공고일: 2026.10.7", "마감: 2026년 10월 7일"),
    ("2026.10.7", "마감: 2026년 10월 7일"),
    ("국문 접수마감: 2027.1.21 16:00", "영문 접수마감: 2027년 1월 21일 16:00"),
    ("공고일: 2026.10.7\n접수마감: 2026.10.7", "2026년 10월 7일"),
])
def test_unknown_or_conflicting_role_is_not_inferred(quote, display):
    assert korean_display(quote, quote, display, kind="deadline") is None


@pytest.mark.parametrize("title", [
    "입상작 발표", "추가 선정 공고", "지정변경 및 지정취소 공고", "개발계획 변경 승인",
])
def test_result_and_approval_notices_do_not_demand_new_application_deadlines(title):
    assert informational_notice(title, "결과를 안내합니다.")
    assert not informational_notice(title, "이의신청 접수기간: 2026.10.12 ~ 2026.10.20")


def test_arbitrary_country_caption_is_preserved_and_cannot_be_replaced():
    quote = "스페인 신청기한: 2027-01-29 14:00 (현지시간)"
    assert source_schedule_rows(quote) == [quote]
    assert korean_display(
        quote, quote, "신청기한: 2027년 1월 29일 14:00 (현지시간)", kind="deadline"
    ) == "스페인 신청기한: 2027년 1월 29일 14:00 (현지시간)"
    assert korean_display(
        quote, quote, "이탈리아 신청기한: 2027년 1월 29일 14:00 (현지시간)", kind="deadline"
    ) is None


def test_wrapped_receipt_rule_stays_with_its_source_date():
    quote = "접수기간: 2026-10-14 18:00까지\n(도착분 기준)\n접수방법: 온라인 접수"
    assert source_schedule_rows(quote) == ["접수기간: 2026-10-14 18:00까지 (도착분 기준)"]


def test_country_prefix_cannot_consume_previous_line_condition():
    assert source_schedule_rows("접수방법: 국내 중소기업\n접수기간: 2026-10-14") == [
        "접수기간: 2026-10-14",
    ]


@pytest.mark.parametrize("other", [
    "접수기간: 2026.11.26 " + "긴 조건 설명 " * 30,
    "접수기간: 2026.02.30",
    "국외 접수기간: 2026.10.26 " + "긴 조건 설명 " * 30,
])
def test_unreadable_second_schedule_cannot_hide_a_possible_conflict(other):
    doc = document("접수기간: 2026.10.26\n" + other, saved="2026.10.26")
    assert "deadline" not in verified_submission_facts(doc)


def test_flattened_preceding_field_does_not_become_a_country_caption():
    assert source_schedule_rows("신청대상: 국내 중소기업 접수기간: 2026.10.26") == [
        "접수기간: 2026.10.26",
    ]
