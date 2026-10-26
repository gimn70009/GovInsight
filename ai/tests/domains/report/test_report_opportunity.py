import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.report.amounts import readable_amount_source
from app.domains.report.brief import BriefFactsModelOutput, build_context, validate_brief
from app.domains.report.evidence import select_report_evidence
from app.domains.report.opportunity import source_opportunity_facts
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_brief import output, run, sourced
from tests.domains.report.test_report_fact_reuse import request as verified_request
from tests.domains.report.test_report_template import with_document


def document(title, text=""):
    return with_document(title=title, contentText=text).documents[0]


@pytest.mark.parametrize("title,kind,business_type", [
    ("국제공동 연구개발 지원사업 공고", "사업 기회", "R&D·기술개발"),
    ("컨설팅 지원 대상기업 모집", "사업 기회", "경영·전문지원"),
    ("컨설팅 수행기관 모집", "사업 기회", "용역·조달"),
    ("컨설팅 수행기관 선정 결과", "일반 공지", None),
    ("창업지원 사업 운영기관 모집", "사업 기회", "위탁운영"),
    ("정보시스템 개발 용역 입찰 공고", "사업 기회", "용역·조달"),
    ("인사발령 안내", "일반 공지", None),
    ("시스템 점검 안내", "일반 공지", None),
    ("정책 발표", "일반 공지", None),
    ("새로운 소식", "확인 필요", None),
])
def test_notice_nature_uses_solicitation_role_not_document_kind(title, kind, business_type):
    doc = document(title, "사업 예산은 5억 원입니다.")
    facts = source_opportunity_facts(doc)
    assert facts.notice_kind == kind
    assert facts.business_type == business_type


@pytest.mark.parametrize("text,expected,absent", [
    ("지원규모: 연구개발비 연 최대 5억 원 지원", "연구개발비 지원", "투자"),
    ("지원규모: 보증지원 최대 5억 원", "보증", "융자"),
    ("지원규모: 보조금 지원 없음, 자부담 100%", None, "보조금"),
    ("지원규모: 융자 제외, 보증지원 최대 5억 원", "보증", "융자"),
])
def test_financing_methods_preserve_distinctions_and_negation(text, expected, absent):
    facts = source_opportunity_facts(document("정책자금 지원 공고", text))
    assert absent not in (facts.business_method or "")
    if expected:
        assert expected in facts.business_method


def test_investment_matching_does_not_invent_direct_investment():
    facts = source_opportunity_facts(document("투자유치 설명회", "투자유치 연계 안내"))
    assert facts.business_method != "투자"


def test_business_amount_keeps_annual_limit_period_and_cofunding_without_total_math():
    text = "지원규모: 과제당 연 최대 5억 원, 최대 3년\n※ 자부담 25% 이상, 부가세 별도"
    req = with_document(title="연구개발 지원사업 공고", contentText=text)
    facts = source_opportunity_facts(req.documents[0])
    assert facts.amount == readable_amount_source(text)
    body = TemplateReportGenerator().generate(req).summary
    assert "공고 성격: 사업 기회" in body
    assert "사업 유형: R&D·기술개발" in body
    assert "• 금액·규모:" in body and "자부담 25% 이상" in body
    assert "15억" not in body


def test_general_notice_omits_opportunity_placeholders_but_preserves_actual_action():
    req = with_document(
        title="행정예고 의견 제출 안내", summary="기존 핵심요약을 그대로 유지합니다.",
        contentText="의견 제출기한: 2026년 10월 30일까지\n접수방법: 온라인 접수",
    )
    body = TemplateReportGenerator().generate(req).summary
    assert "공고 성격: 일반 공지" in body
    assert "기존 핵심요약을 그대로 유지합니다." in body
    assert "사업 유형:" not in body and "사업 방식:" not in body
    assert "금액·규모" not in body and "참여 역할" not in body
    assert "2026년 10월 30일" in body and "온라인 접수" in body


def test_generated_classification_and_amount_cannot_use_form_or_unrelated_quote():
    req = with_document(
        title="연구개발 지원사업 공고", contentText="신청대상: 국내 중소기업",
        attachments=[{"fileName": "신청서 양식.hwp", "downloadUrl": "https://example.org/form",
                      "extractedText": "지원규모: 100억 원"}],
    )
    doc = req.documents[0]
    result = output(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        business_types=[{"value": "수출·해외진출", "evidence": {
            "source_id": "source-0", "quote": "신청대상: 국내 중소기업"}}],
        amounts=[sourced("지원규모: 100억 원", source_id="source-1")],
    )
    brief = validate_brief(result, build_context(doc, 16000), doc)
    assert brief.opportunity.business_type == "R&D·기술개발"
    assert brief.opportunity.amount is None
    assert "business_type:unsupported_classification" in brief.validation_issues
    assert "amount:unsupported_quote" in brief.validation_issues


def test_model_amount_quote_recovers_adjacent_financial_conditions():
    text = "지원규모: 과제당 연 최대 5억 원, 최대 3년\n※ 자부담 25% 이상"
    doc = document("연구개발 지원사업 공고", text)
    result = output(amounts=[sourced("지원규모: 과제당 연 최대 5억 원, 최대 3년")])
    brief = validate_brief(result, build_context(doc, 16000), doc)
    assert brief.opportunity.amount.endswith("자부담 25% 이상")


def test_old_complete_submission_facts_still_request_missing_business_fields():
    req = verified_request(saved=True)
    req.documents[0].title = "기술개발 지원사업 공고"
    fields = json.loads(build_context(req.documents[0], 16000).payload)["requested_fields"]
    assert "business_methods" in fields and "amounts" in fields
    assert "applicants" not in fields and "documents" not in fields
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    run(req, runner)
    runner.extract.assert_awaited_once()


def test_model_schema_legacy_values_default_new_fields_to_empty():
    result = BriefFactsModelOutput(applicants=[], deadlines=[], destinations=[], contacts=[])
    assert result.extracted().amounts == []
    assert result.extracted().notice_kinds == []


def test_report_selection_reserves_amount_and_method_evidence():
    text = ("배경 설명입니다. " * 200 + "\n\n지원규모: 기업당 바우처 5천만 원\n"
            "※ 자부담 20% 이상\n\n접수기간: 2026년 10월 30일까지")
    selected = select_report_evidence(text, 350)
    assert "기업당 바우처 5천만 원" in selected.text and "자부담 20% 이상" in selected.text


def test_saved_analysis_amount_without_current_source_is_not_evidence():
    doc = document("기술개발 지원사업 공고")
    doc.comparison_summary = with_document(comparisonSummary={
        "supportScale": "100억 원", "requiredPartner": "해외기관 필수",
    }).documents[0].comparison_summary
    assert doc.comparison_summary.support_scale == "100억 원"
    assert doc.comparison_summary.required_partner == "해외기관 필수"
    assert source_opportunity_facts(doc).amount is None


@pytest.mark.parametrize("condition", [
    "- 지원기간: 최대 3년\n- 단, 예산 사정에 따라 지원액 조정",
    "○ 지원기간: 최대 3년\n※ 자부담 25% 이상",
])
def test_wrapped_bullet_financial_conditions_are_never_dropped(condition):
    text = "지원한도: 과제당 연 최대 5억 원\n" + condition
    doc = document("연구개발 지원사업 공고", text)
    facts = source_opportunity_facts(doc)
    assert "최대 3년" in facts.amount
    assert ("지원액 조정" in facts.amount) or ("자부담 25% 이상" in facts.amount)
    result = output(amounts=[sourced(text)])
    brief = validate_brief(result, build_context(doc, 16000), doc)
    assert brief.opportunity.amount == facts.amount


def test_in_kind_contribution_is_not_a_provided_benefit():
    doc = document("연구개발과제 지원 공고", (
        "지원규모: 정부지원연구개발비 과제당 3억 원 이내\n"
        "기관부담연구개발비는 현금 및 현물로 구성하며 현물 지원은 제공하지 않습니다."
    ))
    assert source_opportunity_facts(doc).business_method == "연구개발비 지원"
    result = output(business_methods=[{"value": "현물·시설", "evidence": {
        "source_id": "source-0", "quote": doc.content_text.splitlines()[1]}}])
    brief = validate_brief(result, build_context(doc, 16000), doc)
    assert "현물" not in brief.opportunity.business_method


def test_result_and_new_follow_on_solicitation_is_still_business():
    doc = document("창업지원 사업 선정 결과 및 추가 모집 공고", (
        "잔여 예산으로 창업기업을 추가 모집합니다.\n지원금: 기업당 최대 1억 원"
    ))
    assert source_opportunity_facts(doc).notice_kind == "사업 기회"
    assert source_opportunity_facts(doc).amount == "지원금: 기업당 최대 1억 원"


def test_historical_result_quote_cannot_downgrade_current_solicitation():
    doc = document("창업기업 모집 공고", "지난해 선정 결과 5개 기업에 지원했습니다.")
    result = output(notice_kinds=[{"value": "일반 공지", "evidence": {
        "source_id": "source-0", "quote": doc.content_text}}])
    brief = validate_brief(result, build_context(doc, 16000), doc)
    assert brief.opportunity.notice_kind == "사업 기회"
    assert "notice_kind:unsupported_classification" in brief.validation_issues


def test_operator_does_not_inherit_beneficiary_subsidy_amount_or_method():
    doc = document("창업지원 운영기관 모집 공고", "선정된 창업기업에 보조금 1억 원 지원")
    assert source_opportunity_facts(doc).amount is None
    result = output(
        business_methods=[{"value": "보조금", "evidence": {
            "source_id": "source-0", "quote": doc.content_text}}],
        amounts=[sourced(doc.content_text)],
    )
    brief = validate_brief(result, build_context(doc, 16000), doc)
    assert brief.opportunity.amount is None
    assert "보조금" not in (brief.opportunity.business_method or "")


def test_table_cap_is_recovered_with_budget_adjustment_but_not_employment_ratio():
    text = ("지원규모 및 기간\n프로그램\n대상국가\n지원 자격\n국제공동연구\n"
            "과제당 연 5억원 이내 (최대 3년)\n국내 기업 참여 필수\n"
            "* 지원예산 및 지원기간은 평가 결과에 따라 변동될 수 있음\n"
            "5. 기타 조건\n연구개발비 5억원 당 1명 이상의 청년을 신규채용")
    doc = document("국제공동 기술개발사업 공고", text)
    facts = source_opportunity_facts(doc)
    assert facts.amount.startswith("과제당 연 5억 원 이내 (최대 3년)")
    assert "변동될 수 있습니다" in facts.amount and "신규채용" not in facts.amount
    result = output(amounts=[sourced("과제당 연 5억원 이내 (최대 3년)", quote=text)])
    brief = validate_brief(result, build_context(doc, 16000), doc)
    assert brief.opportunity.amount == facts.amount


def test_award_in_financing_domain_does_not_inherit_financing_method():
    doc = document("정책금융 지원 유공자 포상 공고", "신용보증 업무 유공자를 포상합니다.")
    assert source_opportunity_facts(doc).business_method == "상금·포상"


def test_final_provider_classification_does_not_reintroduce_beneficiary_fallback():
    doc = document("창업지원 모집 공고", (
        "수행기관 모집: 창업 컨설팅 용역 수행기관을 모집합니다.\n"
        "지원금: 선정된 창업기업에 보조금 1억 원 지원"
    ))
    result = output(business_types=[{"value": "용역·조달", "evidence": {
        "source_id": "source-0", "quote": doc.content_text.splitlines()[0]}}])
    brief = validate_brief(result, build_context(doc, 16000), doc)
    assert brief.opportunity.business_type == "용역·조달"
    assert brief.opportunity.amount is None
    assert "보조금" not in (brief.opportunity.business_method or "")


@pytest.mark.parametrize("title,expected", [
    ("공공기관 기간제 근로자 채용 공고", "일반 공지"),
    ("직원 채용지원금 지원사업 모집 공고", "사업 기회"),
    ("인력공급 용역 입찰 공고", "사업 기회"),
])
def test_individual_staff_recruitment_is_not_company_employment_support(title, expected):
    doc = document(title, "채용인원: 행정원 2명\n응시자격: 만 18세 이상 개인\n"
                   "응시원서 접수기간: 2026년 10월 20일까지")
    assert source_opportunity_facts(doc).notice_kind == expected


@pytest.mark.parametrize("title,expected", [
    ("연구개발 지원사업 공고", True),
    ("홈페이지 서비스 점검 안내", False),
    ("새로운 소식", False),
])
def test_only_business_cards_show_localized_unknown_amount_and_participation(title, expected):
    req = with_document(title=title, contentText="상세 안내를 참고하십시오.")
    body = TemplateReportGenerator().generate(req).summary
    assert ("• 금액·규모: 원문 확인 필요" in body) is expected
    assert ("• 제출 주체·대상: 원문 확인 필요" in body) is expected


@pytest.mark.parametrize("boundary", ["*", "※", "•", "- "])
def test_table_financial_note_stops_before_unrelated_guideline_bullet(boundary):
    cap = "과제당 연 5억원 이내 (최대 3년)"
    note = "* 지원예산 및 지원기간은 평가 결과에 따라 변동될 수 있음"
    text = (cap + "\n국내 기업 참여 필수\n" + note + "\n" + boundary
            + " 프로그램 가이드라인 https://example.org/rules/을 준수하여야 함")
    doc = document("국제공동 기술개발사업 공고", text)
    facts = source_opportunity_facts(doc)
    assert facts.amount == readable_amount_source(cap + " " + note)
    result = output(amounts=[sourced(cap, quote=text)])
    brief = validate_brief(result, build_context(doc, 16000), doc)
    assert brief.opportunity.amount == facts.amount


@pytest.mark.parametrize("repeat,accepted", [(18, True), (50, False)])
def test_long_table_financial_note_is_complete_or_rejected_never_cut(repeat, accepted):
    cap = "과제당 연 5억원 이내 (최대 3년)"
    note = "* 지원예산은 " + "평가 결과와 예산 사정에 따라 " * repeat + "조정될 수 있음"
    text = cap + "\n국내 기업 참여 필수\n" + note
    doc = document("국제공동 기술개발사업 공고", text)
    facts = source_opportunity_facts(doc)
    assert len(note) > 250
    assert facts.amount == (readable_amount_source(cap + " " + note) if accepted else None)
