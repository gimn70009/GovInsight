import pytest

from app.domains.report.applicants import checkbox_applicants
from app.domains.report.brief import (
    BriefFactsModelOutput,
    build_context,
    fallback_brief,
    validate_brief,
)
from app.domains.report.enrichment import _PROMPT_VERSION
from app.domains.report.presentation import korean_display, submission_display_lines
from app.domains.report.schedules import schedule_fields
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_brief import output, sourced
from tests.domains.report.test_report_template import with_document

CHOICES = (
    "주관기관유형□ 제한없음 □ 대학/출연(연)/국공립연/특정연 ■ 기업 "
    "□ 기타 비영리법인(병원 등) □ 외국법인 "
    "주관기관 외 필수참여기관■ 제한없음 □ 기업 "
    "□ 기타 비영리법인(병원 등) □ 외국법인"
)
SELECTED = ["주관기관: 기업", "주관기관 외 필수 참여기관: 제한 없음"]


@pytest.mark.parametrize("source", [CHOICES, CHOICES.replace("□", "\n□").replace("■", "\n■")])
def test_attached_and_wrapped_checkboxes_preserve_selected_roles(source):
    assert checkbox_applicants(source) == SELECTED
    assert submission_display_lines(source, "제출 주체·대상") == SELECTED


@pytest.mark.parametrize("tail", [
    "□ 기업 □ 대학", "■ 기업 ■ 대학", "■ 제한없음 ■ 기업 □ 대학",
    "■ 기업 □ 기업", "■ 공공기관 □ 기업",
])
def test_uncertain_checkbox_rows_are_not_flattened_into_eligible_categories(tail):
    assert checkbox_applicants("주관기관" + tail) == ["주관기관: 원문 확인 필요"]


@pytest.mark.parametrize("value", [
    "□ 기업 ■ 대학", "□ 기업 ■ 대학 단, 업력 3년 이상", "□ 지방자치단체 ■ 공공기관",
])
def test_roleless_choices_are_unknown_even_with_conditions_or_unknown_categories(value):
    result = "\n".join(submission_display_lines(value, "제출 주체·대상"))
    assert "원문 확인 필요" in result
    assert "기업" not in result and "대학" not in result
    if "3년" in value:
        assert "단, 업력 3년 이상" in result


@pytest.mark.parametrize("condition", [
    "단, 주관기관이 부도 상태인 경우 제외",
    "※ 공동기관은 중소기업 필수",
    "다만, 주관기관은 업력 3년 이상이며 참여제한 기관은 제외",
])
def test_role_mentions_inside_conditions_do_not_create_new_rows(condition):
    result = checkbox_applicants("주관기관 □ 기업 ■ 대학 " + condition)
    assert result == ["주관기관: 대학. " + condition]


def test_mixed_checkbox_and_plain_role_preserves_plain_eligibility_conditions():
    assert checkbox_applicants(
        "주관기관 □ 기업 ■ 대학 공동기관: 기업 (업력 3년 이상)"
    ) == ["주관기관: 대학", "공동기관: 기업 (업력 3년 이상)"]


def test_duplicate_roles_and_one_unknown_role_do_not_inherit_another_selection():
    result = checkbox_applicants("주관기관 □ 대학 ■ 기업 공동기관 □ 대학 □ 기업")
    assert result == ["주관기관: 기업", "공동기관: 원문 확인 필요"]
    assert checkbox_applicants(
        "주관기관 □ 대학 ■ 기업 주관기관 ■ 대학 □ 기업"
    ) == ["주관기관: 원문 확인 필요"]


@pytest.mark.parametrize("modern", [True, False])
def test_verified_full_quote_overrides_a_fragment_picked_from_an_unselected_cell(modern):
    req = with_document(contentText=CHOICES)
    candidate = sourced("주관기관유형 제한없음", CHOICES)
    candidate["fragments"] = ["주관기관유형", "제한없음"]
    if modern:
        candidate["display_text"] = "주관기관: 제한 없음"
    brief = validate_brief(output(applicants=[candidate]), build_context(req.documents[0], 16000),
                           req.documents[0])
    assert brief.facts.applicant == "\n".join(SELECTED)


def test_new_model_allows_full_choice_quote_without_truncating_evidence():
    quote = CHOICES + (
        " 단, 참여제한 기관은 신청할 수 없습니다. "
        "해외기관은 필수 참여기관으로 신청하여야 합니다."
    )
    model = BriefFactsModelOutput(
        applicants=[{"evidence": {"source_id": "source-0", "quote": quote},
                     "fragments": [], "display_text": "주관기관: 기업"}],
        deadlines=[], destinations=[], contacts=[],
    )
    assert len(quote) > 150
    assert model.extracted().applicants[0].evidence.quote == quote
    assert _PROMPT_VERSION == "submission-brief-ko-v17-purpose-and-recovery"


@pytest.mark.parametrize("mode", ["direct", "fallback", "model"])
def test_report_paths_keep_checkbox_states_and_roles(mode):
    req = with_document(contentText="신청자격:\n" + CHOICES)
    brief = None
    if mode == "fallback":
        brief = fallback_brief(req.documents[0], "제출 안내 정리 실패")
    elif mode == "model":
        candidate = sourced(CHOICES) | {"display_text": "주관기관: 기업"}
        brief = validate_brief(
            output(applicants=[candidate]), build_context(req.documents[0], 16000),
            req.documents[0],
        )
    body = TemplateReportGenerator().generate(req, briefs={2: brief} if brief else None).summary
    for line in SELECTED:
        assert line in body
    assert "□" not in body and "■" not in body and "비영리법인" not in body


def test_safe_plain_display_is_used_but_cannot_omit_a_required_condition():
    quote = "신청 자격: 기업 ㅇ 업력 3년 이상 ㅇ 단, 참여제한 기관은 제외"
    display = "기업\n업력 3년 이상\n단, 참여제한 기관은 제외"
    assert korean_display(quote, quote, display, kind="applicant") == display
    assert korean_display(quote, quote, "기업: 업력 3년 이상", kind="applicant") is None


@pytest.mark.parametrize("value", ["공고일: 2026.10.6", "공고일 2026.10.6", "게시일: 2026.10.6"])
def test_announcement_is_not_a_submission_deadline(value):
    fields = schedule_fields(value)
    assert fields["공고일"] and not fields["제출·의견 기한"]


@pytest.mark.parametrize("value", [
    "접수 시작: 2026.10.6", "신청 개시: 2026.10.6", "설명회: 2026.10.6", "2026.10.6",
])
def test_nonclosing_dates_keep_their_role_without_becoming_deadlines(value):
    fields = schedule_fields(value)
    assert fields["관련 일정"] and not fields["제출·의견 기한"]


@pytest.mark.parametrize("mode", ["direct", "fallback", "model"])
def test_announcement_only_displays_explicit_unknown_submission_deadline(mode):
    req = with_document(contentText="공고일: 2026.10.6")
    brief = None
    if mode == "fallback":
        brief = fallback_brief(req.documents[0], "정리 실패")
    elif mode == "model":
        candidate = sourced("공고일: 2026.10.6") | {"display_text": "공고일: 2026년 10월 6일"}
        brief = validate_brief(
            output(deadlines=[candidate]), build_context(req.documents[0], 16000), req.documents[0],
        )
    body = TemplateReportGenerator().generate(req, briefs={2: brief} if brief else None).summary
    assert "• 공고일: 2026년 10월 6일" in body
    assert "• 제출·의견 기한: 원문 확인 필요" in body
    assert "제출·의견 기한: 공고일" not in body


@pytest.mark.parametrize("mode", ["direct", "fallback"])
def test_announcement_never_displaces_domestic_or_foreign_deadlines(mode):
    req = with_document(contentText="공고일: 2026.10.6\n국내 접수마감: 2026.10.20 18:00까지\n"
                                   "해외 접수마감: 2026.10.22 24:00까지 (소인 기준)")
    brief = fallback_brief(req.documents[0], "정리 실패") if mode == "fallback" else None
    body = TemplateReportGenerator().generate(req, briefs={2: brief} if brief else None).summary
    assert "• 공고일: 2026년 10월 6일" in body
    assert "국내 접수마감: 2026년 10월 20일 18:00까지" in body
    assert "해외 접수마감: 2026년 10월 22일 24:00까지 (소인 기준)" in body


@pytest.mark.parametrize("source,display", [
    ("공고일: 2026.10.6", "접수 마감: 2026년 10월 6일"),
    ("접수 시작: 2026.10.6", "접수 마감: 2026년 10월 6일"),
    ("설명회: 2026.10.6", "접수 마감: 2026년 10월 6일"),
    ("공고일/접수마감: 2026.10.6 / 2026.10.31", "접수 마감: 2026년 10월 6일"),
])
def test_model_cannot_relabel_a_verified_date_role(source, display):
    assert korean_display(source, source, display, kind="deadline") is None


def test_sentence_break_does_not_move_deadline_receipt_condition_to_other_schedule():
    value = "접수 마감: 2026년 10월 30일입니다. 우편은 도착분만 유효합니다."
    fields = schedule_fields(value)
    assert fields["제출·의견 기한"] == [value]
    assert fields["관련 일정"] == []


def test_announcement_then_deadline_keeps_each_dates_following_conditions():
    value = (
        "공고일: 2026년 10월 6일입니다. 게시 일정입니다.\n"
        "접수 마감: 2026년 10월 30일입니다. 우편은 도착분만 유효합니다."
    )
    fields = schedule_fields(value)
    assert fields["공고일"] == ["2026년 10월 6일입니다. 게시 일정입니다."]
    assert fields["제출·의견 기한"] == [
        "접수 마감: 2026년 10월 30일입니다. 우편은 도착분만 유효합니다."
    ]
    assert fields["관련 일정"] == []



def test_screenshot_duplicate_unlabelled_choice_group_never_confirms_unrestricted_eligibility():
    source = (
        "주관기관유형□ 제한없음 □ 대학/출연(연)/국공립연/특정연 □ 기업 "
        "□ 기타 비영리법인(병원 등) □ 외국법인"
        "주관기관 외 필수참여기관■ 제한없음 □ 기업 "
        "□ 기타 비영리법인(병원 등) □ 외국법인 "
        "□ 제한없음 □ 기업 □ 기타 비영리법인(병원 등) □ 외국법인"
    )
    req = with_document(contentText=source)
    model = BriefFactsModelOutput(
        applicants=[{"evidence": {"source_id": "source-0", "quote": source},
                     "fragments": [], "display_text": "주관기관 외 필수 참여기관: 제한 없음"}],
        deadlines=[], destinations=[], contacts=[],
    )
    brief = validate_brief(model.extracted(), build_context(req.documents[0], 16000),
                           req.documents[0])
    assert brief.facts.applicant == (
        "주관기관: 원문 확인 필요\n주관기관 외 필수 참여기관: 원문 확인 필요"
    )
    body = TemplateReportGenerator().generate(req, briefs={2: brief}).summary
    assert "주관기관 외 필수 참여기관: 원문 확인 필요" in body
    assert "제한 없음" not in body and "제한없음" not in body
    assert "비영리법인" not in body and "□" not in body


@pytest.mark.parametrize("value", [
    "접수 기한: 공고일로부터 30일 이내", "접수: 상시 접수", "마감: 추후 안내", "상시 접수",
])
def test_named_schedule_without_calendar_date_is_not_appended_to_announcement(value):
    fields = schedule_fields("공고일: 2026.10.6\n" + value)
    assert fields["공고일"] == ["2026년 10월 6일"]
    assert fields["제출·의견 기한"] == [value]


def test_role_colon_inside_an_exception_does_not_split_the_condition():
    condition = "다만, 주관기관: 부도 상태인 경우 제외"
    assert checkbox_applicants("주관기관 □ 기업 ■ 대학 " + condition) == [
        "주관기관: 대학. " + condition,
    ]


def test_duplicate_date_does_not_attach_following_condition_to_a_different_country():
    fields = schedule_fields(
        "국내 접수마감: 2026.10.20\n해외 접수마감: 2026.10.22\n"
        "국내 접수마감: 2026.10.20\n우편은 도착분만 유효합니다."
    )
    assert fields["제출·의견 기한"] == [
        "국내 접수마감: 2026년 10월 20일",
        "해외 접수마감: 2026년 10월 22일",
        "국내 접수마감: 2026년 10월 20일 우편은 도착분만 유효합니다.",
    ]
