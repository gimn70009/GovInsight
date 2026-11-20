import json
from pathlib import Path

import pytest

from app.domains.participation import completeness, focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source

CASES = json.loads(
    (
        Path(__file__).resolve().parents[4] / "test-fixtures/participation-completeness-cases.json"
    ).read_text(encoding="utf-8")
)


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["name"])
def test_shared_completeness_cases(case):
    assert (
        completeness.review_reason(case["text"], case["details"], case["quotes"], case["kind"])
        == case["reason"]
    )


def request(sources):
    return RewriteRequest(
        sources=sources, criteria=[Criterion(code="BUSINESS_FORM", label="주체", category="자격")]
    )


def test_partial_applicant_table_is_unresolved_and_full_rows_are_grounded():
    source = Source(
        id="s1",
        group_label="신청자격",
        excerpt="□ 신청자격\n[표]\n구 분\t자 격\n대학\t국내 대학\n연구기관\t비영리 연구기관\n[/표]",
    )
    req = request([source])
    payload = json.loads(focused.model_messages([source])[1].content)
    assert payload["sources"][0]["context"]["required_rows"] == ["L4", "L5"]
    items = [
        focused.ExtractedCondition(
            source_id="s1", criterion="BUSINESS_FORM", kind="ELIGIBILITY", text=text, quote=line
        )
        for text, line in [
            ("국내 대학이 신청할 수 있습니다.", "L4"),
            ("비영리 연구기관이 신청할 수 있습니다.", "L5"),
        ]
    ]
    partial = focused.project(
        focused.Extraction(items=items[:1], unresolved_source_ids=[]), [source], req
    )
    assert not partial.statements[0].review_required
    assert partial.status == "PARTIAL" and partial.evidence_only_ids == ["s1"]
    complete = focused.project(
        focused.Extraction(items=items, unresolved_source_ids=[]), [source], req
    )
    assert len(complete.statements) == 2
    assert not any(s.review_required for s in complete.statements)
    assert complete.evidence_only_ids == []


def test_coverage_does_not_turn_support_amount_table_into_applicants():
    source = Source(
        id="s1", group_label="지원규모", excerpt="[표]\n구분\t지원규모\n일반\t2억원\n[/표]"
    )
    assert completeness.qualification_rows(source) == []


def test_checklist_requires_linked_exemptions_within_unchanged_budget():
    checklist = Source(
        id="c",
        group_label="[공고 대상] 기관 모집\n[자격 확인서] 적정성 확인서",
        excerpt="□ 동시 과제 수 여부\n연구책임자가 3개를 초과하는가?",
    )
    exception = Source(
        id="e",
        group_label="[공고 대상] 기관 모집\n[확인서 적용 예외] 적정성 확인서",
        excerpt="* 참여연구원은 동시 과제 수 산정에서 제외합니다.",
    )
    assert {s.id for s in focused.select_sources(request([checklist, exception]))} == {"c", "e"}
    oversize = exception.model_copy(update={"excerpt": exception.excerpt + "예외 설명 " * 3000})
    assert not focused.select_sources(request([checklist, oversize]))


def test_other_recruitment_exemption_does_not_block_independent_checklist():
    source = Source(
        id="c",
        group_label="[공고 대상] 대학 모집\n[자격 확인서] 자격 확인서",
        excerpt="□ 자격 여부\n국내 대학인가?",
    )
    other = Source(
        id="e",
        group_label="[공고 대상] 기관 모집\n[확인서 적용 예외] 자격 확인서",
        excerpt="예외 " * 5000,
    )
    assert [s.id for s in focused.select_sources(request([source, other]))] == ["c"]


def test_hidden_fragment_cannot_satisfy_legal_alternative_coverage():
    quote = "연구기관\t「연구기관법」 제2조 기관 또는 「산업혁신법」 제42조 연구소"
    source = Source(id="s1", group_label="신청자격", excerpt=quote)
    item = focused.ExtractedCondition(
        source_id="s1",
        criterion="BUSINESS_FORM",
        kind="ELIGIBILITY",
        text="연구기관은 다음 중 하나에 해당해야 합니다.",
        quote="L1",
        details=[
            focused.ExtractedDetail(source_id="s1", quote="L1", text=text)
            for text in ["연구기관법 제2조 기관입니다.", "산업혁신법 제42조 연구소"]
        ],
    )
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), [source], request([source])
    )
    assert result.statements[0].review_required
    assert result.evidence_only_ids == ["s1"]


def test_reference_checklist_precedes_long_application_procedure():
    from app.domains.participation import selection

    main = Source(id="m", group_label="신청자격", excerpt="□ 신청자격\n비영리 연구기관 신청 가능")
    checklist = Source(
        id="c",
        group_label="[자격 확인서] 자격 확인서",
        excerpt="□ 채무불이행 여부\n기업이 체납 상태인가? 단, 재창업 지원기업은 예외",
    )
    procedure = Source(
        id="p",
        group_label="□ 신청방법",
        excerpt="□ 신청방법\n신청기업은 허위서류를 제출하면 지원 제외 대상입니다.",
    )
    budget = sum(len(s.excerpt.encode()) for s in [main, checklist])
    selected = selection.select(
        request([procedure, main, checklist]), focused.source_fields, focused.CODES, budget, 24
    )
    assert {s.id for s in selected} == {"m", "c"}
