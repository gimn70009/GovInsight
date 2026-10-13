from app.domains.report.facts import SubmissionFacts
from app.domains.report.opportunity import OpportunityFacts, source_opportunity_facts
from app.domains.report.semantics import field_evidence_valid, source_contacts
from app.domains.report.summary import purpose_summary
from tests.domains.report.test_report_template import with_document


def document(summary, *, title="국제공동 기술개발사업 공고", content=""):
    return with_document(summary=summary, title=title, contentText=content).documents[0]


def facts(deadline="", destination=""):
    return SubmissionFacts(applicant="", deadline=deadline, destination=destination, contact="")


def opportunity(amount=None):
    return OpportunityFacts(notice_kind="사업 기회", amount=amount)


def test_repeated_amount_deadline_and_route_leave_the_saved_purpose():
    purpose = "국내 기업과 해외 연구기관의 공동 기술개발을 지원하는 사업입니다."
    summary = (purpose + " 과제당 연 최대 5억 원을 최대 3년 지원합니다. "
               "국문 접수 마감은 2027년 1월 21일입니다. 접수는 K-PASS 온라인으로 진행합니다.")
    doc = document(summary)
    result = purpose_summary(
        doc, facts("국문 접수 마감: 2027년 1월 21일", "K-PASS 온라인 접수"),
        opportunity("과제당 연 최대 5억 원, 최대 3년 지원"),
    )
    assert result == purpose
    assert doc.summary == summary


def test_mixed_rfp_scope_is_preserved_while_verified_money_and_period_are_separated():
    scope = "모집 RFP는 양자 하드웨어와 양자컴퓨팅 응용 소프트웨어·시스템 2개"
    detail = "과제당 정부출연금은 총 9.03억 원 내외이며 과제기간은 2026년~2029년입니다."
    doc = document(scope + "로, " + detail)
    assert purpose_summary(doc, facts(), opportunity(detail)) == scope + "입니다."


def test_unverified_money_is_not_restored_from_another_display_field():
    purpose = "국제공동 연구개발을 지원합니다."
    summary = purpose + " 과제당 연 최대 5억 원을 지원합니다."
    doc = document(summary)
    assert purpose_summary(doc, facts(), opportunity()) == purpose
    assert purpose_summary(doc, facts(), opportunity("과제당 총 최대 5억 원 지원")) == purpose


def test_policy_change_and_before_after_numbers_remain_the_subject():
    summary = "지원한도가 3억 원에서 5억 원으로 확대되었습니다."
    doc = document(summary, title="지원제도 개정 안내", content=summary)
    assert purpose_summary(doc, facts(), OpportunityFacts(notice_kind="일반 공지")) == summary
    assert purpose_summary(doc, facts(), opportunity(summary)) == summary


def test_deadline_exception_and_distinct_country_or_missing_date_are_not_lost():
    for detail in (
        "영문 접수 마감은 2027년 1월 21일입니다.",
        "국문 접수 마감은 2027년 1월 22일입니다.",
        "국문 접수 마감은 2027년 1월 21일이며, 다만 우편 소인분만 인정합니다.",
    ):
        summary = "국제공동 연구개발을 지원합니다. " + detail
        assert purpose_summary(document(summary), facts("국문 접수 마감: 2027년 1월 21일"),
                               opportunity()) == summary


def test_distinct_route_obligations_and_postal_order_are_preserved():
    summary = "공동 연구개발을 지원합니다. 접수는 온라인 제출 후 우편으로 진행합니다."
    assert purpose_summary(document(summary), facts(destination="온라인 접수"),
                           opportunity()) == summary
    assert purpose_summary(document(summary), facts(destination="우편 제출 후 온라인 접수"),
                           opportunity()) == summary


def test_all_detail_summary_is_not_replaced_with_an_invented_purpose():
    summary = "과제당 연 최대 5억 원을 지원합니다."
    assert purpose_summary(
        document(summary, content=summary), facts(), opportunity(summary),
    ) == summary


def test_contact_project_name_is_not_promoted_to_a_department():
    project = "사업명 : 퀀텀(양자)플랫폼 양자활용연구거점 양자공동연구실(JQL)"
    content = ("7. 문의처\n" + project + "\n사업담당부서: 연구지원실\n"
               "사업지원팀: 02-0000-0000, help@example.org")
    doc = document("사업 안내입니다.", content=content)
    recovered = source_contacts(doc)
    assert project not in (recovered or "")
    assert "연구지원실" in recovered
    assert "02-0000-0000" in recovered and "help@example.org" in recovered
    assert not field_evidence_valid("contact", project, content, doc)


def test_project_name_with_department_suffix_is_not_a_contact():
    for project in ("세부사업명: 연구기반조성", "과제명: 양자공동연구실", "사업명: 국제협력사업실"):
        doc = document("사업 안내입니다.", content="문의 담당부서\n" + project)
        assert source_contacts(doc) is None
        assert not field_evidence_valid("contact", project, doc.content_text, doc)


def test_department_must_be_a_complete_department_name_not_a_word_fragment():
    text = "문의처\n연구과제의 접수 절차를 안내함\n담당부서: 연구지원과"
    doc = document("사업 안내입니다.", content=text)
    recovered = source_contacts(doc)
    assert recovered == "담당부서: 연구지원과"


def test_completed_designation_is_ordinary_despite_historical_solicitation_words():
    doc = document("기관 지정 결과입니다.", title="건축물관리지원센터 지정 고시",
                   content="건축물관리법에 따라 지원센터를 지정하여 고시합니다. "
                   "기존 공모에서는 위탁운영 기관을 모집하였으며 선정 절차를 완료했습니다.")
    assert source_opportunity_facts(doc).notice_kind == "일반 공지"
    assert source_opportunity_facts(doc).business_type is None


def test_current_designation_and_operation_competitions_remain_opportunities():
    for title, content in (
        ("건축물관리지원센터 운영기관 모집 공고", "위탁운영 기관을 모집합니다."),
        ("건축물관리지원센터 지정 고시 및 위탁운영 공모", "운영기관을 모집합니다."),
        ("건축물관리지원센터 지정 고시",
         "센터 지정을 희망하는 기관은 신청하세요. 위탁운영 기관 모집"),
    ):
        assert source_opportunity_facts(document("기관을 모집합니다.", title=title,
                                                content=content)).notice_kind == "사업 기회"


def test_general_opinion_notice_keeps_policy_scope_but_removes_covered_deadline_and_routes():
    purpose = "건축물 관리 기준을 개정해 점검 대상을 5층 이상 건축물로 확대하는 행정예고입니다."
    detail = "의견 제출 기한은 2026년 11월 1일이며 우편 또는 전자우편으로 제출합니다."
    doc = document(purpose + " " + detail, title="건축물 관리 기준 개정 행정예고")
    info = OpportunityFacts(notice_kind="일반 공지")
    covered = facts("의견 제출 기한: 2026년 11월 1일", "우편 또는 이메일 제출")
    assert purpose_summary(doc, covered, info) == purpose
    assert purpose_summary(doc, facts(covered.deadline, "우편 제출"), info) == doc.summary
    sequential = facts(covered.deadline, "우편 제출 후 이메일 제출")
    assert purpose_summary(doc, sequential, info) == doc.summary


def test_mixed_policy_scope_with_opinion_details_preserves_policy_numbers():
    purpose = "점검 주기를 3년에서 2년으로 바꾸는 개정안"
    detail = "의견 제출 기한은 2026년 11월 1일이며 우편 또는 이메일로 제출합니다."
    doc = document(purpose + "이며, " + detail, title="건축물 관리 기준 개정 행정예고")
    assert purpose_summary(doc, facts("의견 제출 기한: 2026년 11월 1일", "우편 또는 이메일 제출"),
                           OpportunityFacts(notice_kind="일반 공지")) == purpose + "입니다."


def test_business_eligibility_numbers_are_not_treated_as_funding_duplicates():
    summary = "지원한도는 매출 5억 원 이상 기업만 신청할 수 있는 기준과 구분됩니다."
    doc = document(summary, content=summary)
    assert purpose_summary(doc, facts(), opportunity("매출 5억 원 이상")) == summary


def test_identical_channel_cannot_hide_different_named_portal_or_postal_place():
    for claim, actual in (
        ("접수는 K-PASS 온라인으로 진행합니다.", "IRIS 온라인 접수"),
        ("접수는 서울청사로 우편 제출합니다.", "세종청사 우편 접수"),
    ):
        summary = "국제공동 연구개발을 지원하는 사업입니다. " + claim
        result = purpose_summary(document(summary), facts(destination=actual), opportunity())
        assert result == summary


def test_named_portal_matches_case_and_punctuation_without_changing_identity():
    purpose = "국제공동 연구개발을 지원하는 사업입니다."
    doc = document(purpose + " 접수는 K-PASS 온라인으로 진행합니다.")
    assert purpose_summary(doc, facts(destination="kpass 온라인 접수"), opportunity()) == purpose


def test_issuer_before_contact_section_is_not_promoted_using_later_contact_heading():
    text = "산업통상부\n7. 문의 담당부서\n연구지원실"
    doc = document("사업 안내입니다.", content=text)
    assert not field_evidence_valid("contact", "산업통상부", text, doc)
    assert field_evidence_valid("contact", "연구지원실", text, doc)


def test_deadline_cannot_borrow_the_date_from_another_language_row():
    purpose = "국제공동 연구개발을 지원하는 사업입니다."
    details = facts("국문 접수 마감: 2027년 11월 5일\n영문 접수 마감: 2027년 11월 6일")
    for claim in (
        "영문 접수 마감은 2027년 11월 5일입니다.",
        "국문 접수 마감은 2027년 11월 6일입니다.",
        "접수 마감은 2027년 11월 5일입니다.",
    ):
        doc = document(purpose + " " + claim)
        assert purpose_summary(doc, details, opportunity()) == doc.summary
    matched = document(purpose + " 영문 접수 마감은 2027년 11월 6일입니다.")
    assert purpose_summary(matched, details, opportunity()) == purpose


def test_flattened_multiple_role_dates_cannot_be_combined_to_prove_a_claim():
    summary = "국제공동 연구개발을 지원합니다. 영문 접수 마감은 2027년 11월 5일입니다."
    actual = "국문 접수 마감: 2027년 11월 5일 영문 접수 마감: 2027년 11월 6일"
    assert purpose_summary(document(summary), facts(actual), opportunity()) == summary


def test_mixed_deadline_and_route_share_the_claimed_language_scope():
    purpose = "국제공동 연구개발을 지원하는 사업입니다."
    claim = "영문 접수 마감은 2027년 11월 6일이며 K-PASS 온라인으로 접수합니다."
    deadlines = "국문 접수 마감: 2027년 11월 5일\n영문 접수 마감: 2027년 11월 6일"
    mismatched = "국문: K-PASS 온라인 접수\n영문: IRIS 온라인 접수"
    matched = "국문: IRIS 온라인 접수\n영문: K-PASS 온라인 접수"
    doc = document(purpose + " " + claim)
    assert purpose_summary(doc, facts(deadlines, mismatched), opportunity()) == doc.summary
    assert purpose_summary(doc, facts(deadlines, matched), opportunity()) == purpose
    unlabelled = facts(deadlines, "K-PASS 온라인 접수")
    assert purpose_summary(doc, unlabelled, opportunity()) == doc.summary


def test_deadline_time_cannot_be_borrowed_from_the_route_office_hours():
    summary = ("국제공동 연구개발을 지원합니다. "
               "영문 접수 마감은 2027년 11월 6일 16:00이며 온라인으로 접수합니다.")
    actual = facts("영문 접수 마감: 2027년 11월 6일 17:00", "영문: 온라인 접수, 문의 16:00")
    assert purpose_summary(document(summary), actual, opportunity()) == summary
    with_comma = summary.replace("6일 16:00", "6일, 16:00")
    assert purpose_summary(document(with_comma), actual, opportunity()) == with_comma


def test_route_identity_cannot_be_assembled_from_different_language_rows():
    summary = "국제공동 연구개발을 지원합니다. 접수는 영문 K-PASS 온라인으로 진행합니다."
    actual = facts(destination="국문: K-PASS 온라인 접수\n영문: IRIS 온라인 접수")
    assert purpose_summary(document(summary), actual, opportunity()) == summary
