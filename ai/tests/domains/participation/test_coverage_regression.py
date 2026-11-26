"""Original response replays and condition-level failure isolation; no external calls."""

import json
from pathlib import Path

import pytest

from app.domains.participation import coverage, focused, selection
from app.domains.participation.schemas import Criterion, RewriteRequest, Source

FIXTURES = Path(__file__).parent / "fixtures"


def request(*sources):
    return RewriteRequest(sources=list(sources), criteria=[
        Criterion(code=code, label=code, category="자격") for code in focused.CODES
    ])


def item(source_id, quote, text, **kwargs):
    return focused.ExtractedCondition(source_id=source_id, quote=quote, text=text,
                                      criterion="EXPERIENCE", kind="ELIGIBILITY", **kwargs)


def project(req, items, selected=None):
    return focused.project(focused.Extraction(items=items, unresolved_source_ids=[]),
                           req.sources if selected is None else selected, req)


def test_missing_program_and_missing_role_are_partial_without_hiding_supported_sibling():
    first = Source(id="a", group_label="[상위 구역] 성장사업\n□ 신청자격",
                   excerpt=("□ 신청자격\n◦ 주관기관은 수행 역량이 있어야 합니다.\n"
                            "◦ 참여기업은 국내 중소기업이어야 합니다."))
    second = Source(id="b", group_label="[상위 구역] 도약사업\n□ 지원대상",
                    excerpt="□ 지원대상\n국내 중견기업이 신청할 수 있습니다.")
    result = project(request(first, second), [
        item("a", "L2", "주관기관은 수행 역량이 있어야 합니다.")
    ])
    assert result.status == "PARTIAL"
    assert not result.statements[0].review_required
    assert set(result.evidence_only_ids) == {"a", "b"}
    assert result.diagnostics["PROGRAM_UNCOVERED"] == 1
    assert result.diagnostics["REQUIREMENT_UNCOVERED"] == 2
    payload = json.loads(focused.model_messages([first, second])[1].content)
    assert payload["sources"][0]["context"]["required_spans"] == ["L2", "L3"]


def test_equivalent_prose_is_accepted_when_all_table_cells_are_explicit():
    source = Source(id="a", group_label="신청자격", excerpt=(
        "□ 신청자격\n◦ 전담인력 2명 이상 필수\n[표]\n구분\t인원 수\t요건\n"
        "전담인력\t2인 이상\t필수\n[/표]"
    ))
    result = project(request(source), [
        item("a", "L2", "전담인력은 2인 이상 필수로 구성해야 합니다.")
    ])
    assert result.status == "READY"
    assert not result.statements[0].review_required
    assert result.evidence_only_ids == []


def test_broad_citation_does_not_hide_rows_and_missing_count_only_reviews_its_own_claim():
    source = Source(id="a", group_label="신청자격", excerpt=(
        "[표]\n구분\t인원 수\t요건\n전담인력\t2인 이상\t필수\n겸직인력\t1인\t필수\n[/표]"
    ))
    complete = project(request(source), [item("a", "L3-L4",
        "전담인력은 2인 이상, 겸직인력은 1인을 필수로 구성해야 합니다.")])
    assert complete.status == "READY"
    incomplete = project(request(source), [
        item("a", "L3", "전담인력은 2인 이상 필수로 구성해야 합니다."),
        item("a", "L4", "겸직인력을 구성해야 합니다."),
    ])
    assert incomplete.status == "PARTIAL"
    assert [s.review_required for s in incomplete.statements] == [False, True]
    assert incomplete.diagnostics["ROW_VALUES_INCOMPLETE"] == 1


def test_header_does_not_make_different_body_content_an_equivalent_row():
    source = Source(id="a", group_label="신청자격", excerpt=(
        "◦ 전담인력 2인 이상 필수\n[표]\n구분\t자격\t인원\n전담인력\t경력 5년 이상\t2인 이상\n[/표]"
    ))
    result = project(request(source), [
        item("a", "L1", "전담인력은 2인 이상 필수로 구성해야 합니다.")
    ])
    assert result.status == "PARTIAL"
    assert result.diagnostics["APPLICANT_ROW_UNCOVERED"] == 1
    assert not result.statements[0].review_required


def test_unselected_input_is_reported_separately_and_duplicate_sources_do_not_count_twice():
    first = Source(id="a", group_label="신청자격", excerpt="국내 대학이 신청할 수 있습니다.")
    duplicate = first.model_copy(update={"id": "duplicate"})
    huge = Source(id="b", group_label="신청자격",
                  excerpt="연구기관은 사업 수행 역량이 있어야 합니다. " * 1000)
    req = request(first, duplicate, huge)
    selected = focused.select_sources(req)
    assert [s.id for s in selected] == ["a"]
    result = project(req, [item("a", "L1", first.excerpt)], selected)
    assert result.status == "PARTIAL"
    assert result.diagnostics["INPUT_SOURCE_UNSELECTED"] == 1
    assert not result.statements[0].review_required


def test_missing_source_or_fabricated_quote_remains_rejected():
    source = Source(id="a", group_label="신청자격", excerpt="국내 대학이 신청할 수 있습니다.")
    for bad in [item("a", "L2", source.excerpt), item("unknown", "L1", source.excerpt)]:
        result = project(request(source), [bad])
        assert result.status == "UNAVAILABLE" and not result.statements


def test_context_retains_nested_prerequisite_exception_and_excludes_unrelated_sibling():
    source = Source(id="a", group_label="지원내용", excerpt=(
        "◦ 지원내용\n- 기반 시설이 부족한 기업은 사전 준비과정을 먼저 거쳐 지원\n"
        "- 위 과정의 인정 범위는 공동 운영 시설을 포함\n* 단, 기존 확인서는 재사용 가능\n"
        "◦ 문의\n담당부서에 문의\n◦ 지원조건\nAI 유형과 일반 유형 중 하나를 선택하여 신청"
    ))
    prepared, _ = selection.prepare(source, focused.source_fields)
    assert "사전 준비과정을 먼저 거쳐" in prepared.excerpt
    assert "공동 운영 시설을 포함" in prepared.excerpt
    assert "기존 확인서는 재사용 가능" in prepared.excerpt
    assert "담당부서에 문의" not in prepared.excerpt


RESPONSES = json.loads((FIXTURES / "eligibility-responses-20261120.json").read_text("utf-8"))
REQUESTS = {row["version"]: row["request"] for row in json.loads(
    (FIXTURES / "eligibility-loss-20261120.json").read_text("utf-8")
)}


@pytest.mark.parametrize("case", RESPONSES, ids=lambda c: str(c["version"]))
def test_actual_responses_preserve_supported_bodies_and_report_remaining_omissions(case):
    req = RewriteRequest.model_validate(REQUESTS[case["version"]])
    selected = [Source.model_validate(source) for source in case["selected"]]
    extracted = focused.Extraction.model_validate(case["extraction"])
    result = focused.project(extracted, selected, req)
    assert result.status == "PARTIAL"
    assert result.diagnostics["REQUIREMENT_UNCOVERED"] > 0
    assert [s.text for s in result.statements] == [s.text for s in extracted.items]
    if case["version"] == 4:
        staffing = [s for s in result.statements if s.source_ids[0] in {"s10", "s16"}]
        assert len(staffing) == 2 and not any(s.review_required for s in staffing)
    elif case["version"] == 6:
        assert not next(s for s in result.statements if s.source_ids == ["s14"]).review_required
        assert result.diagnostics["PROGRAM_UNCOVERED"] > 0
        missing = coverage.audit(selected, result.statements)
        assert {"s17", "s33", "s40", "s41"} <= missing.keys()
    else:
        # The one genuinely incomplete numerical summary stays under review.
        assert sum(s.review_required for s in result.statements) == 1
        assert result.diagnostics["ROW_VALUES_INCOMPLETE"] == 1
