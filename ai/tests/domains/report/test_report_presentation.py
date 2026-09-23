from app.domains.report.brief import build_context, validate_brief
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_brief import output, sourced
from tests.domains.report.test_report_template import with_document


def check(raw, korean, field="applicants", quote=None):
    req = with_document(contentText=quote or raw)
    value = sourced(raw, quote)
    value["display_text"] = korean
    brief = validate_brief(
        output(**{field: [value], "documents": []}),
        build_context(req.documents[0], 16000),
        req.documents[0],
    )
    return brief, req


def test_korean_translation_keeps_original_evidence_but_not_english_prose():
    raw = "Lead applicants in Korea: Small and Medium Enterprises and Middle-standing Enterprises"
    brief, req = check(raw, "국내 주관기관: 중소·중견기업")
    assert brief.facts.applicant == "국내 주관기관: 중소·중견기업"
    body = TemplateReportGenerator().generate(req, briefs={2: brief}).summary
    assert "Small" not in body and "중소·중견기업" in body


def test_korean_rewrite_cannot_invent_or_drop_a_percentage():
    for korean in ["중소기업: 정부지원금 비율 70% 이상", "중소기업: 참여 가능"]:
        brief, _ = check("중소기업 정부지원금 비율 50% 이상", korean)
        assert brief.facts.applicant == "원문 확인 필요"
    brief, _ = check("중소기업 정부지원금 비율 50% 이상", "중소기업: 정부지원금 비율 50% 이상")
    assert "50%" in brief.facts.applicant


def test_translated_contact_cannot_change_email_or_phone():
    raw = "Project office: 02-0000-0000 help@example.org"
    brief, _ = check(raw, "국내 문의: 02-0000-0000 other@example.org", "contacts")
    assert brief.facts.contact == "원문 확인 필요"
    brief, _ = check(raw, "국내 문의: 02-0000-0000 help@example.org", "contacts")
    assert "help@example.org" in brief.facts.contact


def test_country_routes_are_separate_lines_and_urls_keep_slashes():
    source = (
        "한국 접수: https://example.go.kr/apply/path\n해외 접수: https://example.org/apply/path"
    )
    req = with_document(contentText=source)
    items = [sourced(s) | {"display_text": s} for s in source.splitlines()]
    brief = validate_brief(
        output(destinations=items), build_context(req.documents[0], 16000), req.documents[0]
    )
    body = TemplateReportGenerator().generate(req, briefs={2: brief}).summary
    assert "• 제출처·방법: 한국 접수: https://example.go.kr/apply/path" in body
    assert "  ↳ 해외 접수: https://example.org/apply/path" in body


def test_result_notice_does_not_show_missing_application_fields():
    req = with_document(title="공동연구사업 선정 결과 안내", contentText="선정 결과를 안내합니다.")
    body = TemplateReportGenerator().generate(req).summary
    assert "미확인" not in body and "안내:" not in body
    req = with_document(title="선정 결과 및 이의신청 안내", contentText="이의신청을 접수합니다.")
    assert "안내:" in TemplateReportGenerator().generate(req).summary


def test_raw_english_fallback_is_not_exposed_as_report_prose():
    req = with_document(
        contentText="지원대상: Small and Medium Enterprises and Middle standing Enterprises"
    )
    body = TemplateReportGenerator().generate(req).summary
    assert "Small and Medium" not in body


def test_failed_model_keeps_short_fields_without_long_raw_paragraphs():
    from app.domains.report.brief import fallback_brief

    req = with_document(
        contentText="접수마감: 2027-01-28 16:00\n제출처: "
        + "온라인에서 입력하고 모든 자료를 제출합니다. " * 10
    )
    brief = fallback_brief(req.documents[0], "제출 안내 자동 정리 실패")
    assert "2027-01-28" in brief.facts.deadline
    assert brief.facts.destination == "원문 확인 필요"
    body = TemplateReportGenerator().generate(req, briefs={2: brief}).summary
    assert "모든 자료를 제출합니다" not in body
    assert "게시글 보기" in body and "안내:" in body


def test_broken_table_eligibility_is_not_shown_without_korean_rewrite():
    from app.domains.report.presentation import readable_source

    assert (
        readable_source("주관연구 영리기관 자 개발기관 격 공동연구 제한 없음", "applicant") is None
    )


def test_model_emits_evidence_once_and_local_code_derives_verbatim_value():
    from app.domains.report.brief import BriefModelOutput, DisplayEvidence

    source = "국내주관기관자격 국내공동기관자격\n중소·중견 제한없음"
    item = DisplayEvidence(
        evidence={"source_id": "source-0", "quote": source},
        fragments=["국내주관기관자격", "중소·중견"],
        display_text="국내 주관기관: 중소·중견기업",
    )
    model = BriefModelOutput(
        applicants=[item], deadlines=[], destinations=[], contacts=[], documents=[]
    )
    assert "text" not in DisplayEvidence.model_fields
    result = model.extracted()
    assert result.applicants[0].text == "국내주관기관자격 중소·중견"
    req = with_document(contentText=source)
    assert (
        validate_brief(
            result, build_context(req.documents[0], 16000), req.documents[0]
        ).facts.applicant
        == item.display_text
    )
    # Forged fragments are still rejected, even though the display is readable.
    item.fragments = ["국내주관기관자격", "대기업"]
    assert (
        validate_brief(
            model.extracted(), build_context(req.documents[0], 16000), req.documents[0]
        ).facts.applicant
        == "원문 확인 필요"
    )


def test_verified_display_recovers_deadline_time_from_the_same_quote():
    quote = "국내 접수기한 ~‘27. 1. 28.(목) 16:00까지 (한국표준시간)"
    brief, _ = check(
        "~‘27. 1. 28.(목)", "국내 마감: 2027-01-28 16:00까지 (한국표준시간)", "deadlines", quote
    )
    assert "16:00" in brief.facts.deadline
    brief, _ = check("~‘27. 1. 28.(목)", "국내 마감: 2027-01-28 17:00까지", "deadlines", quote)
    assert brief.facts.deadline == "원문 확인 필요"


def test_combined_labelled_routes_split_without_breaking_urls():
    raw = "한국 접수처: https://example.go.kr/apply/path — 해외 접수처: https://example.org/apply/path"
    brief, _ = check(raw, raw, "destinations")
    assert brief.facts.destination == raw.replace(" — ", "\n")


def test_english_month_is_normalized_without_losing_the_date():
    brief, _ = check("January 28, 2027", "마감: 2027년 1월 28일까지", "deadlines")
    assert "2027년 1월 28일" in brief.facts.deadline
    brief, _ = check("January 28, 2027", "마감: 2027년 2월 28일까지", "deadlines")
    assert brief.facts.deadline == "원문 확인 필요"


def test_percentage_direction_cannot_be_reversed_in_korean_display():
    brief, _ = check("정부지원금 비율 50% 이상", "정부지원금 비율: 50% 이하")
    assert brief.facts.applicant == "원문 확인 필요"


def test_labeled_until_date_is_not_mistaken_for_a_broken_range():
    quote = "국내 접수기한 ~‘27. 1. 28.(목) 16:00까지 (한국표준시간)"
    brief, _ = check(
        "~‘27. 1. 28.(목)",
        "국내 접수기한: ~‘27. 1. 28.(목) 16:00까지 (한국표준시간)",
        "deadlines",
        quote,
    )
    assert "16:00" in brief.facts.deadline
    brief, _ = check(
        "2027. 1. 28. ~ 월요일까지", "국내 접수기한: 2027. 1. 28. ~ 월요일까지", "deadlines"
    )
    assert brief.facts.deadline == "원문 확인 필요"


def test_korean_routes_keep_explicit_unilateral_submission_exclusion():
    source = (
        "국내기관은 KIAT에, 해외기관은 해외 전문기관에 동시에 과제를 신청하여야\n"
        "접수가 인정되며, 한 쪽 기관에만 신청할 경우 사전 제외\n"
        "연구개발계획서 접수 (한국) www.k-pass.kr\n(스페인) CDTI 홈페이지"
    )
    brief, _ = check(
        "연구개발계획서 접수 (한국) www.k-pass.kr",
        "한국 접수: www.k-pass.kr",
        "destinations",
        source,
    )
    assert "동시에" in brief.facts.destination and "사전 제외" in brief.facts.destination
