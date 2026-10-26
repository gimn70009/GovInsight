"""Saved summary money requires independent current-source evidence."""

import pytest

from app.domains.report.amounts import (
    monetary_claim_supported,
    monetary_source_rows,
    validated_amount_display,
)
from app.domains.report.brief import BriefContext, BriefModelOutput, build_context, validate_brief
from app.domains.report.facts import SubmissionFacts
from app.domains.report.opportunity import OpportunityFacts, source_opportunity_facts
from app.domains.report.submission_documents import SourcePart
from app.domains.report.summary import purpose_summary
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_template import with_document

PURPOSE = "산학연의 양자기술 공동연구를 지원하는 사업입니다."
SOURCE = "과제당 총 정부출연금은 9.03억 원 내외입니다."
FACTS = SubmissionFacts(applicant="", deadline="", destination="", contact="")
OPPORTUNITY = OpportunityFacts(notice_kind="사업 기회")


def doc(summary, source="", **extra):
    return with_document(title="양자 공동연구 신규과제 공고", summary=summary,
                         contentText=source, **extra).documents[0]


@pytest.mark.parametrize("claim", [
    "과제당 총 정부출연금은 90.3억 원 내외입니다.",
    "과제당 연 정부출연금은 9.03억 원 내외입니다.",
    "과제당 총 정부출연금은 최대 9.03억 원입니다.",
    "과제당 총 정부출연금은 9.03억 원입니다.",
    "과제당 총 정부출연금은 9.03억 원 내외이며 연차별로 분할 지급합니다.",
])
def test_false_value_scope_bound_or_unstated_installments_do_not_enter_report_summary(claim):
    document = doc(PURPOSE + " " + claim, SOURCE)
    assert purpose_summary(document, FACTS, OPPORTUNITY) == PURPOSE
    assert document.summary.endswith(claim)


def test_verified_approximate_total_survives_even_with_equivalent_currency_units():
    source = "과제당 총 정부출연금은 903백만 원 내외입니다."
    document = doc(PURPOSE + " " + SOURCE, source)
    assert purpose_summary(document, FACTS, OPPORTUNITY) == document.summary
    assert monetary_claim_supported(source, SOURCE)


def test_source_conditions_cannot_disappear_from_a_financial_assertion():
    source = SOURCE + " 단, 정부예산의 국회 심의 결과에 따라 금액이 축소될 수 있습니다."
    assert not monetary_claim_supported(source, SOURCE)
    assert monetary_claim_supported(source, source)


def test_saved_comparison_and_opportunity_values_do_not_replace_missing_primary_evidence():
    document = doc(PURPOSE + " " + SOURCE, PURPOSE,
                   comparisonSummary={"supportScale": SOURCE})
    amount = OpportunityFacts(notice_kind="사업 기회", amount=SOURCE)
    assert purpose_summary(document, FACTS, amount) == PURPOSE


def test_reference_and_form_examples_cannot_prove_current_funding():
    for name in ("참고 운영지침.pdf", "사업계획서 작성예시.hwp"):
        document = doc(PURPOSE + " " + SOURCE, PURPOSE, attachments=[{
            "fileName": name, "downloadUrl": "https://example.org/reference",
            "extractedText": SOURCE,
        }])
        assert purpose_summary(document, FACTS, OPPORTUNITY) == PURPOSE


def test_real_notice_attachment_can_prove_current_funding():
    document = doc(PURPOSE + " " + SOURCE, PURPOSE, attachments=[{
        "fileName": "신규과제 공고문.pdf", "downloadUrl": "https://example.org/notice",
        "extractedText": SOURCE,
    }])
    assert purpose_summary(document, FACTS, OPPORTUNITY) == document.summary


def test_detail_only_fallback_never_resurrects_unverified_amount():
    result = purpose_summary(doc(SOURCE), FACTS, OPPORTUNITY)
    assert "9.03" not in result
    assert "원문 확인" in result


def test_mixed_research_scope_preserves_its_complete_purpose_when_money_is_unverified():
    purpose = "모집 RFP는 양자 하드웨어와 양자컴퓨팅 응용 소프트웨어·시스템 2개"
    result = purpose_summary(doc(purpose + "로, " + SOURCE), FACTS, OPPORTUNITY)
    assert result == purpose + "입니다."


def test_policy_before_after_values_keep_their_order_and_real_subject():
    source = "지원한도가 3억 원에서 5억 원으로 확대되었습니다."
    ordinary = OpportunityFacts(notice_kind="일반 공지")
    assert purpose_summary(doc(source, source), FACTS, ordinary) == source
    wrong = doc(PURPOSE + " 지원한도가 5억 원에서 3억 원으로 확대되었습니다.", source)
    assert purpose_summary(wrong, FACTS, OPPORTUNITY) == PURPOSE


def test_unverified_dates_inside_money_clause_are_not_accepted_from_same_amount_alone():
    claim = "과제당 총 정부출연금은 9.03억 원 내외이며 기간은 2026년~2039년입니다."
    actual = "과제당 총 정부출연금은 9.03억 원 내외이며 기간은 2026년~2029년입니다."
    assert purpose_summary(doc(PURPOSE + " " + claim, actual), FACTS, OPPORTUNITY) == PURPOSE


def test_template_path_checks_saved_summary_without_mutating_saved_analysis():
    request = with_document(title="양자 공동연구 신규과제 공고", summary=PURPOSE + " " + SOURCE,
                            contentText=PURPOSE, comparisonSummary={"supportScale": SOURCE})
    body = TemplateReportGenerator().generate(request).summary
    assert "요약: " + PURPOSE in body
    assert "9.03" not in body
    assert request.documents[0].summary.endswith(SOURCE)


def test_following_and_wrapped_source_notes_cannot_be_dropped_from_summary_money():
    source = (SOURCE + "\n※ 정부예산의 국회 심의 결과에 따라\n"
              "금액이 축소될 수 있습니다.\n□ 신청자격\n기업이 신청할 수 있습니다.")
    assert len(monetary_source_rows(source)) == 3
    assert purpose_summary(doc(PURPOSE + " " + SOURCE, source), FACTS, OPPORTUNITY) == PURPOSE


# Independent official notice, MSIT 2026-0985, printed page 1 (PDF page 3):
# https://www.bizinfo.go.kr/cmm/fms/fileDown.do?atchFileId=FILE_000000000776029&fileSn=1
# Preserve PdfParser's actual line wraps; this is not the older screenshot transcription.
OFFICIAL_JQL_BUDGET = (
    "□ 사업 기간/예산(정부출연금 ):’26년~’29년 / 과제당 총 9.03억원 내외\n"
    "※ 단계평가 결과에 따라, 연구비 조정 또는 과제중단 가능. "
    "중단과제 발생 시, 중단 과제 수 만큼 신규 선정 가능\n"
    "※ ‘27년 정부예산의 국회 심의 결과에 따라 선정과제수 및 예산은 "
    "변동(또는 축소)될 수 있으며, ’27년 예산 추가\n"
    "확보 시, 후순위 과제 추가선정 가능(추가선정과제의 개시일은 "
    "27년 1월 이후가 될 수 있음). 최종 선정\n"
    "통보 시 연차별예산, 개월수 등 일부 변동될 수 있음\n"
    "□ 사업 내용\nㅇ 양자기술의 공동연구를 지원"
)


def test_actual_official_jql_wrapped_budget_preserves_complete_conditions():
    result = source_opportunity_facts(doc(PURPOSE, OFFICIAL_JQL_BUDGET)).amount
    assert result is not None
    for value in ("’26년~’29년", "정부출연금", "과제당 총 9.03억 원 내외",
                  "단계평가", "과제중단", "국회 심의", "후순위", "추가선정",
                  "27년 1월", "최종 선정 통보", "연차별예산", "개월수"):
        assert value in result
    assert result.endswith("변동될 수 있습니다")
    assert "신규 선정 가능합니다. ‘27년" in result
    assert "사업 내용" not in result
    assert "※" not in result and "□" not in result and "/" not in result
    assert "매년" not in result and "확정" not in result


@pytest.mark.parametrize("frequency", ["매년", "연간", "매월", "월간", "매일"])
def test_frequency_cannot_be_inserted_after_a_verified_total(frequency):
    claim = SOURCE.removesuffix("입니다.") + f"이며 {frequency} 지원합니다."
    assert not monetary_claim_supported(SOURCE, claim)
    assert validated_amount_display(SOURCE, claim) is None
    assert purpose_summary(doc(PURPOSE + " " + claim, SOURCE), FACTS, OPPORTUNITY) == PURPOSE


def test_unspecified_duration_cannot_become_a_total_period_amount():
    source = "과제당 정부출연금은 9.03억 원 내외입니다."
    assert not monetary_claim_supported(source, SOURCE)


@pytest.mark.parametrize("heading", ["붙임 1. 사업계획서 작성예시", "[붙임 1] 사업계획서"])
def test_same_file_example_section_cannot_prove_opportunity_or_summary_amount(heading):
    example = "신규예산은 총 9.03억 원 내외입니다."
    document = doc(PURPOSE + " " + example, PURPOSE + "\n" + heading + "\n" + example)
    assert source_opportunity_facts(document).amount is None
    assert purpose_summary(document, FACTS, OPPORTUNITY) == PURPOSE
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-0", "quote": example},
                  "fragments": [], "display_text": example}],
    )
    result = validate_brief(model.extracted(), build_context(document, 16000), document)
    assert result.opportunity.amount is None
    assert "amount:incomplete_context" in result.validation_issues


def test_model_excerpt_without_example_heading_is_checked_against_original_attachment():
    example = "신규예산은 총 9.03억 원 내외입니다."
    name = "사업 공고문.pdf"
    url = "https://example.org/notice"
    original = PURPOSE + "\n붙임 1. 사업계획서 작성예시\n" + example
    document = doc(PURPOSE, attachments=[{
        "fileName": name, "downloadUrl": url, "extractedText": original,
    }])
    selected = BriefContext("{}", {"source-1": SourcePart(name, example, url)})
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-1", "quote": example},
                  "fragments": [], "display_text": example}],
    )
    result = validate_brief(model.extracted(), selected, document)
    assert result.opportunity.amount is None
    assert "amount:incomplete_context" in result.validation_issues


def test_model_excerpt_cannot_omit_following_budget_conditions():
    source = SOURCE + "\n※ 국회 심의 결과에 따라 예산이 축소될 수 있습니다."
    document = doc(PURPOSE, source)
    selected = BriefContext("{}", {"source-0": SourcePart(None, SOURCE)})
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-0", "quote": SOURCE},
                  "fragments": [], "display_text": SOURCE}],
    )
    result = validate_brief(model.extracted(), selected, document)
    assert "amount:unsafe_display" in result.validation_issues
    assert "국회 심의" in result.opportunity.amount
    assert "축소될 수 있습니다" in result.opportunity.amount
