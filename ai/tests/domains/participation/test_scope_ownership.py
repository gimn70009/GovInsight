import json

import pytest

from app.domains.participation import context_labels, coverage, focused, scope_ownership, selection
from app.domains.participation.schemas import Criterion, RewriteRequest, ScopeRegion, Source


def request(source):
    return RewriteRequest(
        sources=[source],
        criteria=[Criterion(code=c, label=c, category="자격") for c in focused.CODES],
    )


@pytest.mark.parametrize(
    "label", ["여러 지역을 연계하여 구성한 협의체", "전문인력을 보유한 제조기업"]
)
def test_applicant_description_is_not_a_program_name(label):
    assert context_labels.program_scope(label, "□ 신청자격\n" + label) is None


@pytest.mark.parametrize(
    "owner", ["산업특화 · 정밀기계 · 가람진흥원", "공동전환사업 · 나래연합", "도약형"]
)
def test_cited_owner_overrides_broad_model_scope(owner):
    quote = "◦ 특정 품목 제조기업은 신청할 수 없습니다."
    source = Source(
        id="s1",
        group_label="신청자격",
        excerpt=quote,
        scope_regions=[ScopeRegion(start=0, end=len(quote), scope=owner)],
    )
    item = focused.ExtractedCondition(
        source_id="s1",
        criterion="SECTOR",
        kind="EXCLUSION",
        text=quote[2:],
        quote="L1",
        scope="일반형 / 도약형",
    )
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), [source], request(source)
    )
    assert result.statements[0].scope == owner
    assert not result.statements[0].review_required


def test_selection_rebases_disjoint_fragments_without_leaking_an_owner():
    excerpt = "소개\n신청자격\n◦ 첫 조건\n업무 소개\n지원대상\n◦ 둘째 조건\n"
    first = excerpt.index("신청자격")
    second = excerpt.index("지원대상")
    source = Source(
        id="s",
        excerpt=excerpt,
        scope_regions=[
            ScopeRegion(start=first, end=excerpt.index("업무 소개"), scope="첫 사업"),
            ScopeRegion(start=second, end=len(excerpt), scope="둘째 사업"),
        ],
    )
    selected = scope_ownership.rebase(
        source, "신청자격\n◦ 첫 조건\n[다음 원문 구간]\n지원대상\n◦ 둘째 조건\n"
    )
    assert scope_ownership.resolve(selected, "◦ 첫 조건") == ("첫 사업", False)
    assert scope_ownership.resolve(selected, "◦ 둘째 조건") == ("둘째 사업", False)
    assert scope_ownership.resolve(selected, "[다음 원문 구간]") == (None, False)
    windows = json.loads(focused.model_messages([selected])[1].content)["sources"][0]["context"][
        "scope_windows"
    ]
    assert windows == [
        {"lines": "L1-L2", "scope": "첫 사업"},
        {"lines": "L3-L3", "scope": None},
        {"lines": "L4-L5", "scope": "둘째 사업"},
    ]


def test_common_and_peer_role_conditions_cannot_inherit_a_sibling_program():
    text = "◦ 중소기업은 신청 가능합니다.\n- 도약형 조건\n◦ 등록 공급기업은 참여 가능합니다."
    start, end = text.index("- 도약형"), text.index("◦ 등록")
    source = Source(
        id="s", excerpt=text, scope_regions=[ScopeRegion(start=start, end=end, scope="도약형")]
    )
    items = [
        focused.ExtractedCondition(
            source_id="s",
            criterion="BUSINESS_FORM",
            kind="ELIGIBILITY",
            text=sentence,
            quote=ref,
            scope="도약형",
        )
        for sentence, ref in [
            ("중소기업은 신청 가능합니다.", "L1"),
            ("등록 공급기업은 참여 가능합니다.", "L3"),
        ]
    ]
    result = focused.project(
        focused.Extraction(items=items, unresolved_source_ids=[]), [source], request(source)
    )
    assert len(result.statements) == 2
    assert all(s.scope is None for s in result.statements)


def test_a_quote_crossing_programs_or_repeated_in_different_owners_is_ambiguous():
    source = Source(
        id="s",
        excerpt="요건\n요건",
        scope_regions=[
            ScopeRegion(start=0, end=3, scope="첫 사업"),
            ScopeRegion(start=3, end=5, scope="둘째 사업"),
        ],
    )
    assert scope_ownership.resolve(source, "요건") == (None, True)
    assert scope_ownership.resolve(source, source.excerpt) == (None, True)


def test_identical_conditions_owned_by_different_programs_are_not_deduplicated():
    quote = "□ 신청자격\n◦ 제조기업만 신청 가능합니다."
    sources = [
        Source(
            id=str(i),
            group_label="신청자격",
            excerpt=quote,
            scope_regions=[ScopeRegion(start=0, end=len(quote), scope=owner)],
        )
        for i, owner in enumerate(["성장사업 · 가람기관", "전환사업 · 나래기관"])
    ]
    req = request(sources[0]).model_copy(update={"sources": sources})
    assert len(selection.candidates(req, focused.source_fields)) == 2


def test_explicit_rejection_and_flattened_exclusion_are_independent_citations():
    source = Source(
        id="s",
        group_label="지원대상",
        excerpt=(
            "※ 서류 자동제출 동의 필수\n* 미동의시 자동 탈락\n"
            "* 특정 품목 제조기업은 제외\n스마트공장\t구축\t설비 지원내용\n"
            "※ 외부 플랫폼 사용이 가능한 기업에 한함\n주관기관명\t가람기관\n"
        ),
    )
    units = coverage.inventory(source)
    assert any(u.ref == "L2" and "자동 탈락" in u.text for u in units)
    assert any(u.ref == "L3" and u.text.endswith("제외") for u in units)
    assert any(u.ref == "L5" and u.text.endswith("한함") for u in units)


def test_scope_checks_preserve_consent_rejection_without_a_notice_specific_name():
    text = "□ 신청자격\n- ‘도약형’은 제조기업만 신청 가능\n* 자료전송 미동의시 자동 탈락\n"
    source = Source(
        id="s",
        excerpt=text,
        scope_regions=[ScopeRegion(start=text.index("- ‘"), end=len(text), scope="도약형")],
    )
    payload = json.loads(focused.model_messages([source])[1].content)
    assert any(
        c["scope"] == "도약형"
        and "미동의시 자동 탈락" in c.get("text", "")
        and c["quote"] == "L2-L3"
        for c in payload["scope_checks"]
    )


@pytest.mark.parametrize("detail_scope", ["전환사업 · 나래기관", None])
def test_detail_cannot_borrow_a_condition_from_another_profile_or_unowned_gap(detail_scope):
    main = "◦ 외부 플랫폼을 사용할 수 있는 기업만 신청 가능합니다.\n"
    detail = "◦ 전담인력은 2명 이상이어야 합니다."
    regions = [ScopeRegion(start=0, end=len(main), scope="전환사업 · 가람기관")]
    if detail_scope:
        regions.append(ScopeRegion(start=len(main), end=len(main + detail), scope=detail_scope))
    source = Source(id="s", excerpt=main + detail, scope_regions=regions)
    item = focused.ExtractedCondition(
        source_id="s",
        criterion="BUSINESS_FORM",
        kind="ELIGIBILITY",
        text=main[2:].strip(),
        quote="L1",
        details=[focused.ExtractedDetail(source_id="s", text=detail[2:], quote="L2")],
    )
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), [source], request(source)
    )
    assert result.statements[0].scope == "전환사업 · 가람기관"
    assert result.statements[0].details == []
    assert result.diagnostics["DETAIL_SCOPE_MISMATCH"] == 1
