import json

import pytest

from app.domains.report.amounts import (
    deduplicate_amounts,
    readable_amount_source,
    recover_amount_quote,
    validated_amount_display,
)
from app.domains.report.brief import BriefModelOutput, build_context, validate_brief
from app.domains.report.opportunity import source_opportunity_facts
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_template import with_document


@pytest.mark.parametrize("source,display", [
    ("o 과제당 연 5억원 이내 (최대 3년)\n"
     "* 지원예산 및 지원기간은 평가 결과에 따라 변동될 수 있음",
     "과제당 연 최대 5억 원을 최대 3년 지원합니다. "
     "지원예산과 지원기간은 평가 결과에 따라 변동될 수 있습니다."),
    ("정부지원 비율 75%, 기관부담 비율 25%",
     "정부가 75%를 지원하고 25%는 기관이 부담합니다."),
    ("지원금: 기업당 5천만원 이내, 자부담 20% 이상, 현금 납부 필수",
     "기업당 지원금은 최대 5천만 원이며, 자부담은 20% 이상입니다. 현금 납부는 필수입니다."),
])
def test_valid_natural_money_prose_is_retained_exactly(source, display):
    assert validated_amount_display(source, display) == display


@pytest.mark.parametrize("source,display", [
    ("총 지원금 5억 원 이내, 지원기간 최대 3년",
     "연 최대 5억 원을 최대 3년 지원합니다."),
    ("과제당 연 5억 원 이내, 최대 3년",
     "사업 전체에 연 5억 원을 최대 3년 지원합니다."),
    ("정부지원 비율 75%, 기관부담 비율 25%",
     "정부가 25%를 지원하며 기관이 75%를 부담합니다."),
    ("정부지원금 1억 원, 기관부담금 2억 원",
     "정부지원금은 2억 원이며 기관부담금은 1억 원입니다."),
    ("계약예산 2억 원(부가세 포함), 자부담 20% 이상",
     "계약예산은 2억 원이며 자부담은 20% 이상입니다."),
    ("계약예산 2억 원(부가세 포함)", "계약예산은 2억 원이며 부가세는 별도입니다."),
    ("과제당 연 5억 원 이내, 최대 3년. "
     "단, 평가 결과와 예산 사정에 따라 지원금과 기간이 조정될 수 있음",
     "과제당 연 최대 5억 원을 최대 3년 지원합니다."),
    ("중소기업에 한해 1억 원 지원하며 대기업은 지원 제외",
     "중소기업과 대기업에 1억 원을 지원합니다."),
    ("자부담 20% 이상, 현금 납부 필수",
     "자부담은 20% 이상이며 현금으로 납부할 수 있습니다."),
    ("사업 A 지원금: 기업당 1억 원 이내", "사업 B 지원금은 기업당 최대 1억 원입니다."),
    ("지원금: 1~3억원", "지원금은 3억 원입니다."),
    ("지원금: 1억원 이내", "지원금은 1억 원 이내이나 지원하지 않습니다."),
    ("지원금: 1억원 이내", "지원금은 1억 원 이내이며 반환 의무는 면제됩니다."),
])
def test_amount_rewrites_cannot_change_scope_obligations_or_financial_qualifiers(source, display):
    assert validated_amount_display(source, display) is None


@pytest.mark.parametrize("left,right", [
    ("사업 A 지원금: 기업당 1억 원 이내", "사업 B 지원금: 기업당 1억 원 이내"),
    ("사업 도약 지원금: 기업당 1억 원 이내", "사업 성장 지원금: 기업당 1억 원 이내"),
    ("신규예산: 총 3.2억 원 이내", "과제당 3.2억 원 이내"),
    ("정부지원금 1억 원", "기관부담금 1억 원"),
    ("지원금: 1~3억원", "지원금: 2~3억원"),
])
def test_same_amount_different_program_scope_or_actor_is_not_deduplicated(left, right):
    assert deduplicate_amounts([left, right]) == [left, right]


ENERGY_SOURCE = (
    "1-3. 지원규모 및 기간\n"
    "o 신규예산 : 총 3.2억원 이내\n"
    "o 지원과제 수 : 1개 과제\n"
    "o 과제별 지원규모 : (총 연구개발기간) 12개월 이내, "
    "(총 정부지원연구개발비) 3.2억원 이내\n"
    "공모방식\n품목지정\n1개\n총 3.2억원\n1년 이내\n3.2억원 이내\n"
    "* 신규예산은 정부지원연구개발비에 한함"
)
ENERGY_DISPLAYS = [
    "신규 예산은 총 3.2억 원 이내이며, 정부지원연구개발비에 한합니다.",
    "과제당 총 정부지원연구개발비는 3.2억 원 이내이고, 총 연구개발기간은 12개월 이내입니다.",
]


def energy_request():
    return with_document(
        title="에너지기술 연구개발과제 지원 공고", contentText=ENERGY_SOURCE,
        attachments=[{"fileName": "공고문.pdf", "downloadUrl": "https://example.org/notice.pdf",
                      "extractedText": ENERGY_SOURCE}],
    )


def test_fixed_model_response_keeps_readable_money_through_full_report_path():
    request = energy_request()
    document = request.documents[0]
    context = build_context(document, 16000)
    assert "amounts" in json.loads(context.payload)["requested_fields"]
    fragments = [
        ["o 신규예산 : 총 3.2억원 이내", "* 신규예산은 정부지원연구개발비에 한함"],
        [ENERGY_SOURCE.splitlines()[3]],
    ]
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-0", "quote": ENERGY_SOURCE},
                  "fragments": pieces, "display_text": display}
                 for pieces, display in zip(fragments, ENERGY_DISPLAYS, strict=True)],
    )
    brief = validate_brief(model.extracted(), context, document)
    assert not any(issue.startswith("amount:") for issue in brief.validation_issues)
    assert brief.opportunity.amount.splitlines() == ENERGY_DISPLAYS
    report = TemplateReportGenerator().generate(request, briefs={document.version_id: brief})
    for display in ENERGY_DISPLAYS:
        assert display in report.summary


def test_source_fallback_removes_outline_and_table_noise_and_repeated_source_rows():
    facts = source_opportunity_facts(energy_request().documents[0])
    assert len(facts.amount.splitlines()) == 2
    assert "정부지원연구개발비에 한함" in facts.amount
    assert "총 연구개발기간: 12개월 이내" in facts.amount
    assert all(noise not in facts.amount for noise in ("1-3.", "o ", "* ", "품목지정"))
    assert readable_amount_source("품목지정 1개 총 3.2억원 1년 이내 3.2억원 이내") is None


def test_wrong_payer_model_prose_falls_back_to_grounded_source():
    text = "정부지원금 1억 원, 기관부담금 2억 원"
    document = with_document(title="연구개발 지원 공고", contentText=text).documents[0]
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-0", "quote": text}, "fragments": [],
                  "display_text": "정부지원금은 2억 원이며 기관부담금은 1억 원입니다."}],
    )
    brief = validate_brief(model.extracted(), build_context(document, 16000), document)
    assert "amount:unsafe_display" in brief.validation_issues
    assert brief.opportunity.amount == text


CONDITIONAL_BUDGET_SOURCE = (
    "□ 사업 기간/예산(정부출연금): 2026년~2029년 / 과제당 총 9.03억 원 내외 "
    "단계평가 결과에 따라, 연구비 조정 또는 과제중단 가능. "
    "중단과제 발생 시, 중단 과제 수 만큼 신규 선정 가능\n"
    "2027년 정부예산의 국회 심의 결과에 따라 선정과제수 및 예산은 변동(또는 축소)될 수 있으며, "
    "2027년 예산 추가확보 시, 후순위 과제 추가선정 가능"
    "(추가선정과제의 개시일은 27년 1월 이후가 될 수 있음). "
    "최종 선정통보 시 연차별예산, 개월수 등 일부 변동될 수 있음"
)
CONDITIONAL_BUDGET_DISPLAY = (
    "사업기간은 2026년~2029년이며, 과제당 총 정부출연금은 9.03억 원 내외입니다. "
    "단계평가 결과에 따라 연구비가 조정되거나 과제가 중단될 수 있습니다. "
    "중단 과제 수만큼 신규 선정할 수 있습니다. "
    "2027년 국회 예산 심의에 따라 선정 과제 수와 예산이 변동·축소될 수 있습니다. "
    "2027년 예산을 추가 확보하면 후순위 과제를 추가 선정할 수 있으며, "
    "개시일은 27년 1월 이후가 될 수 있습니다. "
    "최종 선정 통보 시 연차별 예산과 개월 수가 변동될 수 있습니다."
)


def test_long_conditional_budget_prose_survives_model_schema_and_report():
    request = with_document(title="국제공동 연구개발 지원사업 공고",
                            contentText=CONDITIONAL_BUDGET_SOURCE)
    document = request.documents[0]
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-0", "quote": CONDITIONAL_BUDGET_SOURCE},
                  "fragments": [], "display_text": CONDITIONAL_BUDGET_DISPLAY}],
    )
    brief = validate_brief(model.extracted(), build_context(document, 16000), document)
    assert not any(issue.startswith("amount:") for issue in brief.validation_issues)
    assert brief.opportunity.amount == CONDITIONAL_BUDGET_DISPLAY
    report = TemplateReportGenerator().generate(request, briefs={document.version_id: brief})
    assert CONDITIONAL_BUDGET_DISPLAY in report.summary
    assert "□" not in report.summary and "기간/예산" not in report.summary


def test_equivalent_million_currency_unit_is_readable_without_changing_value():
    source = "□ 정부지원금 903백만 원"
    assert readable_amount_source(source) == "정부지원금 9.03억 원"
    assert validated_amount_display(source, "정부지원금은 9.03억 원입니다.") is not None
    assert validated_amount_display(source, "정부지원금은 90.3억 원입니다.") is None
    assert deduplicate_amounts(["정부지원금 903백만 원", "정부지원금 9.03억 원"]) == [
        "정부지원금 903백만 원",
    ]


@pytest.mark.parametrize("display", [
    "과제당 총 정부출연금은 9.03억 원 이내입니다.",
    "과제당 총 정부출연금은 최대 9.03억 원입니다.",
    "과제당 총 정부출연금은 9.03억 원입니다.",
])
def test_approximate_budget_is_not_a_cap_or_exact_amount(display):
    assert validated_amount_display("과제당 총 정부출연금 9.03억 원 내외", display) is None


@pytest.mark.parametrize("old,new", [
    ("과제가 중단될 수 있습니다", "과제를 계속 지원합니다"),
    ("과제가 중단될 수 있습니다", "과제가 중단됩니다"),
    ("중단 과제 수만큼 신규 선정할 수 있습니다. ", ""),
    ("2027년 예산을 추가 확보하면 후순위 과제를 추가 선정할 수 있으며",
     "2027년 후순위 과제를 추가 선정하며"),
    ("국회 예산 심의에 따라", ""),
    ("최종 선정 통보 시", ""),
    ("연차별 예산과 개월 수", "예산"),
])
def test_long_budget_rewrite_retains_appropriation_cancellation_and_conditional_selection(old, new):
    display = CONDITIONAL_BUDGET_DISPLAY.replace(old, new)
    assert validated_amount_display(CONDITIONAL_BUDGET_SOURCE, display) is None


def test_conditional_budget_source_fallback_has_no_square_or_table_slashes():
    document = with_document(title="국제공동 연구개발 지원 공고",
                             contentText=CONDITIONAL_BUDGET_SOURCE).documents[0]
    amount = source_opportunity_facts(document).amount
    assert amount and "□" not in amount and " / " not in amount
    assert "9.03억 원 내외" in amount
    assert "국회 심의" in amount and "후순위" in amount and "연차별예산" in amount
    assert "가능합니다" in amount and "변동될 수 있습니다" in amount


@pytest.mark.parametrize("display", [
    "□ 정부지원금은 9.03억 원입니다.",
    "1-3. 정부지원금은 9.03억 원입니다.",
    "정부지원금 / 9.03억 원",
])
def test_model_amount_display_cannot_reintroduce_outline_or_table_glyphs(display):
    assert validated_amount_display("정부지원금 903백만 원", display) is None



def test_discontinuous_model_quote_recovers_only_ordered_source_fragments():
    request = energy_request()
    document = request.documents[0]
    fragments = ["신규예산 : 총 3.2억원 이내", ENERGY_SOURCE.splitlines()[3],
                 "* 신규예산은 정부지원연구개발비에 한함"]
    display = ("신규 예산은 총 3.2억원 이내이며, 과제별로는 총 연구개발기간 12개월 이내, "
               "총 정부지원연구개발비 3.2억원 이내입니다. "
               "신규예산은 정부지원연구개발비에 한합니다.")
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-0", "quote": "\n".join(fragments)},
                  "fragments": fragments, "display_text": display}],
    )
    brief = validate_brief(model.extracted(), build_context(document, 16000), document)
    assert not any(issue.startswith("amount:") for issue in brief.validation_issues)
    assert brief.opportunity.amount == display


@pytest.mark.parametrize("source,fragments", [
    ("지원금: 과제당 1억원\n※ 자부담 20%", ["지원금: 과제당 2억원", "※ 자부담 20%"]),
    ("지원금: 과제당 1억원\n※ 자부담 20%", ["※ 자부담 20%", "지원금: 과제당 1억원"]),
    ("지원금: 과제당 1억원\n※ 자부담 20%\n지원금: 과제당 1억원\n※ 자부담 20%",
     ["지원금: 과제당 1억원", "※ 자부담 20%"]),
    ("사업 A 지원금: 1억원\n사업 B 지원금: 2억원",
     ["사업 A 지원금: 1억원", "사업 B 지원금: 2억원"]),
    ("지원금: 1억원" + " 긴 배경 설명" * 200 + "자부담 20%", ["지원금: 1억원", "자부담 20%"]),
])
def test_quote_recovery_rejects_fabricated_reordered_ambiguous_cross_program_or_long_spans(
    source, fragments,
):
    assert recover_amount_quote(source, fragments) is None


def test_provider_amount_role_is_validated_from_source_before_display_paraphrase():
    text = "사업비: 연 2억 원 이내, 부가세 포함"
    document = with_document(title="창업지원 위탁운영 기관 모집", contentText=text).documents[0]
    display = "운영기관에게 지급되는 사업비는 연 2억 원 이내이며, 부가세 포함입니다."
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-0", "quote": text}, "fragments": [],
                  "display_text": display}],
    )
    brief = validate_brief(model.extracted(), build_context(document, 16000), document)
    assert brief.opportunity.amount == display
    assert not any(issue.startswith("amount:") for issue in brief.validation_issues)



def test_source_ordered_award_count_in_budget_quote_is_preserved():
    document = energy_request().documents[0]
    fragments = [ENERGY_SOURCE.splitlines()[index] for index in (1, 2, 3)]
    fragments.append("* 신규예산은 정부지원연구개발비에 한함")
    display = ("신규 총예산은 총 3.2억원 이내이며, 지원과제 수는 1개입니다. "
               "과제별로 총 연구개발기간은 12개월 이내이고, "
               "총 정부지원연구개발비는 3.2억원 이내입니다. "
               "신규예산은 정부지원연구개발비에 한합니다.")
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-0", "quote": "\n".join(fragments)},
                  "fragments": fragments, "display_text": display}],
    )
    brief = validate_brief(model.extracted(), build_context(document, 16000), document)
    assert not any(issue.startswith("amount:") for issue in brief.validation_issues)
    assert brief.opportunity.amount == display



@pytest.mark.parametrize("display,accepted", [
    ("과제당 연 5억원 이내로 지원합니다. 지원기간은 최대 3년입니다. "
     "지원예산 및 지원기간은 명시된 조건 이내에서 제공되며, 평가 결과에 따라 변동될 수 있습니다.",
     True),
    ("과제당 연 5억원 이내로 지원합니다. 지원기간은 최대 3년입니다.", False),
])
def test_unique_single_scoped_money_fragment_restores_original_context_without_losing_conditions(
    display, accepted,
):
    cap = "과제당 연 5억원 이내 (최대 3년)"
    text = ("4. 지원 규모 및 기간, 자격\n프로그램\n대상국가\n연구기관\n" + cap +
            "\n필수 참여기관 조건\n"
            "* 지원예산 및 지원기간은 명시된 조건 이내에서 지원하되, "
            "평가 결과에 따라 변동될 수 있음")
    document = with_document(title="국제공동기술개발사업 공고", contentText=text).documents[0]
    model = BriefModelOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[],
        amounts=[{"evidence": {"source_id": "source-0",
                               "quote": "4. 지원 규모 및 기간, 자격\n연구기관\n" + cap},
                  "fragments": [cap], "display_text": display}],
    )
    brief = validate_brief(model.extracted(), build_context(document, 16000), document)
    assert "amount:unsupported_quote" not in brief.validation_issues
    if accepted:
        assert brief.opportunity.amount == display
        assert "amount:unsafe_display" not in brief.validation_issues
    else:
        assert "amount:unsafe_display" in brief.validation_issues
        assert "평가 결과에 따라 변동될 수 있습니다" in brief.opportunity.amount


@pytest.mark.parametrize("source,fragment", [
    ("과제당 1억원", "과제당 1억원"),
    ("정부지원금은 최대 100억원입니다", "정부지원금은 최대 100억원입니다"),
    ("과제당 연 5억원 이내 (최대 3년)\n과제당 연 5억원 이내 (최대 3년)",
     "과제당 연 5억원 이내 (최대 3년)"),
])
def test_single_fragment_recovery_requires_long_scoped_unique_monetary_fact(source, fragment):
    assert recover_amount_quote(source, [fragment]) is None
