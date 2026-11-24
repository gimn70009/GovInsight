"""Regression cases from the supplied public notice; no model or database calls."""

import json
from pathlib import Path

import pytest

from app.domains.participation import citations, completeness, coverage, focused, selection
from app.domains.participation.schemas import Criterion, RewriteRequest, Source, Statement

FIXTURE = json.loads((Path(__file__).parent / "fixtures/council-eligibility-20261124.json")
                    .read_text("utf-8"))
SOURCES = {data["id"]: Source.model_validate(data) for data in FIXTURE["sources"]}


def request(source):
    return RewriteRequest(sources=[source], criteria=[
        Criterion(code=code, label=code, category="자격") for code in focused.CODES
    ])


def project(source, text, quote, **kwargs):
    return focused.project(focused.Extraction(items=[focused.ExtractedCondition(
        source_id=source.id, criterion="BUSINESS_FORM", kind=kwargs.pop("kind", "ELIGIBILITY"),
        text=text, quote=quote, **kwargs,
    )], unresolved_source_ids=[]), [source], request(source))


def test_role_qualification_table_and_dependent_definitions_survive_selection():
    source = SOURCES["s7"]
    selected = focused.select_sources(request(source))
    assert len(selected) == 1 and selected[0].excerpt == source.excerpt
    assert [ref for ref, _, _ in completeness.qualification_rows(selected[0])] == [
        "L5", "L6", "L7", "L8"
    ]
    empty = focused.project(focused.Extraction(items=[], unresolved_source_ids=[]), selected,
                           request(source))
    assert empty.diagnostics["APPLICANT_ROW_UNCOVERED"] == 4


def test_same_number_for_two_objects_cannot_cover_a_missing_region_requirement():
    source = SOURCES["s0"]
    saved = Statement.model_validate(FIXTURE["statements"][0])
    assert not completeness.row_values_preserved(source.excerpt, [saved.text])
    assert not coverage.covered(coverage.inventory(source)[0], source.id, [saved])
    bad = project(source, saved.text, "L1-L3")
    assert bad.statements[0].review_required and bad.status == "PARTIAL"
    correct = ("5극3특 권역별 성장엔진을 중심으로 2개 이상의 광역지자체에 소재한 "
               "2개 이상의 산단을 업종별·산업별로 연계한 협의체여야 합니다.")
    good = project(source, correct, "L1-L3")
    assert good.status == "READY" and not good.statements[0].review_required


@pytest.mark.parametrize("text", [
    "2개 이상의 광역지자체와 산단으로 구성합니다.",
    "2개 이상의 산단을 연계하며 산단은 2개 이상이어야 합니다.",
    "2개 이하의 광역지자체와 2개 이상의 산단으로 구성합니다.",
])
def test_count_identity_multiplicity_and_comparison_are_independent(text):
    assert not completeness.row_values_preserved(SOURCES["s0"].excerpt, [text])


def test_quantity_subjects_allow_rewording_but_not_swapped_values():
    source = "2개 이상의 광역지자체와 5개 이상의 산단"
    assert not completeness.row_values_preserved(source,
        "5개 이상의 광역지자체와 2개 이상의 산단이어야 합니다.")
    assert completeness.row_values_preserved(source,
        "광역지방자치단체는 최소 2개, 산업단지는 최소 5개여야 합니다.")


def test_required_participant_and_growth_scope_must_exist_in_the_summary():
    statements = [Statement.model_validate(data) for data in FIXTURE["statements"]]
    unit = next(u for u in coverage.inventory(SOURCES["s8"]) if u.ref == "L16")
    assert not coverage.covered(unit, "s8", statements)
    body = "주관지역의 지역산업진흥원은 반드시 참여해야 합니다."
    corrected = Statement(source_ids=["s8"], criterion="BUSINESS_FORM", text=body,
                          group_label="신청자격", supporting_quotes=[unit.text], kind="ELIGIBILITY")
    assert coverage.covered(unit, "s8", [corrected])
    assert not completeness.required_terms_preserved(SOURCES["s0"].excerpt,
        ["2개 이상의 광역지자체에 소재한 2개 이상의 산단을 연계한 협의체여야 합니다."])
    assert not completeness.required_terms_preserved(unit.text,
        ["주관지역의 지역산업진흥원은 참여할 수 있습니다."])


def test_long_merged_bonus_row_can_cite_a_real_cell_and_still_checks_omitted_bonus():
    source = selection.prepare(SOURCES["s10"], focused.source_fields)[0]
    row = next(u for u in coverage.inventory(source) if "사업화촉진기업" in u.text)
    assert len(row.text) == 947
    numbered = citations.numbered(source)
    assert f"{row.ref}:C3:" in numbered
    assert f"{row.ref}:C4:" not in numbered  # Repeated merged cells need not consume input.
    quote = citations.resolve(f"{row.ref}:C3", source)
    assert len(quote) < 600 and quote in SOURCES["s10"].excerpt
    text = "사업화촉진기업 참여 시 1개사당 가점 1점을 부여하며 최대 5점까지 인정합니다."
    incomplete = project(source, text, f"{row.ref}:C3", kind="PREFERENCE")
    assert incomplete.diagnostics["REQUIRED_CONDITION_INCOMPLETE"] == 1
    alternatives = [
        "벤처캐피탈은 「벤처투자 촉진에 관한 법률」에 따른 벤처투자회사 및 벤처투자조합이나 "
        "「여신전문금융업법」에 따른 신기술사업금융전문회사여야 합니다.",
        "액셀러레이터는 「벤처투자 촉진에 관한 법률」에 따른 창업기획자여야 합니다.",
        "산업디자이너는 「산업디자인진흥법」에 따른 산업디자인을 "
        "업으로 영위하는 기업체여야 합니다.",
        "사업화전문회사는 「기술의 이전 및 사업화 촉진에 관한 법률」 제12조의2 제1항 및 "
        "같은 법 시행령 제19조의2에 따라 지정된 사업화 전문회사여야 합니다.",
    ]
    result = project(source, text, f"{row.ref}:C3", kind="PREFERENCE", details=[
        focused.ExtractedDetail(source_id=source.id, text=value, quote=f"{row.ref}:C3")
        for value in alternatives
    ])
    assert len(result.statements) == 1 and not result.statements[0].review_required
    assert "QUOTE_TOO_LONG" not in result.diagnostics
    assert result.statements[0].supporting_quotes == [quote]
    empty = focused.project(focused.Extraction(items=[], unresolved_source_ids=[]), [source],
                           request(source))
    assert empty.diagnostics["APPLICANT_ROW_UNCOVERED"] == 1


def test_a_qualifying_exception_cannot_live_only_in_collapsed_details():
    source = Source(id="one", group_label="신청자격", excerpt=(
        "전문가는 개인별 2개 이내 협의체만 가입 가능\n"
        "- 다만, 사업화촉진기업 소속 전문가는 개수 제한 없이 가입 가능"
    ))
    detail = focused.ExtractedDetail(source_id="one", quote="L2",
        text="사업화촉진기업 소속 전문가는 개수 제한 없이 가입할 수 있습니다.")
    bad = project(source, "전문가는 개인별 2개 이내 협의체만 가입할 수 있습니다.",
                  "L1", details=[detail])
    assert bad.statements[0].review_required
    assert bad.diagnostics["EXCEPTION_NOT_IN_MAIN"] == 1
    omitted = project(source, "전문가는 개인별 2개 이내 협의체만 가입할 수 있습니다.", "L1")
    assert omitted.statements[0].review_required
    assert omitted.diagnostics["EXCEPTION_NOT_IN_MAIN"] == 1
    good = project(source,
        "전문가는 개인별 2개 이내 협의체만 가입할 수 있으나 "
        "사업화촉진기업 소속 전문가는 개수 제한이 없습니다.",
        "L1-L2")
    assert not good.statements[0].review_required


def test_cell_passages_are_exact_contiguous_original_spans_and_reject_invalid_addresses():
    first, second = "원문 정의 " * 110, "단, 예외 기관은 참여 가능합니다."
    source = Source(id="one", group_label="신청자격", excerpt=f"유형\t{first} ⏎ {second}")
    assert citations.resolve("L1:C2:P2", source) == second
    assert "L1:C2:P2:" in citations.numbered(source)
    for invalid in ("L1:C3", "L1:C2:P3", "L2:C1", "L2-L1"):
        assert citations.resolve(invalid, source) == ""


def test_a_cell_cannot_borrow_another_cells_quantity_or_reverse_its_limit():
    source = Source(id="one", group_label="신청자격", excerpt="협의체\t회원 100명 이상")
    for quote, text in [
        ("L1:C1", "협의체 회원은 100명 이상이어야 합니다."),
        ("L1:C2", "협의체 회원은 100명 이하여야 합니다."),
    ]:
        result = project(source, text, quote)
        assert not result.statements
        assert result.diagnostics["QUANTITY_NOT_IN_CITATION"] == 1
