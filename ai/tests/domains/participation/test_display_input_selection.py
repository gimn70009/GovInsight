"""Latest public extraction regressions, without paid model requests."""

import json
from pathlib import Path

import pytest

from app.domains.participation import focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source

FIXTURES = json.loads(
    (Path(__file__).parent / "fixtures/display_input_20261106.json").read_text("utf-8")
)


def request(*sources):
    return RewriteRequest(
        sources=list(sources),
        criteria=[Criterion(code=c, label=c, category="신청 자격") for c in focused.CODES],
    )


@pytest.mark.parametrize("notice_id", [3, 4])
def test_latest_notices_keep_both_program_requirements_and_normal_applicant_source(notice_id):
    row = next(row for row in FIXTURES if row["id"] == notice_id)
    sources = [Source.model_validate(s) for s in row["sources"]]
    selected = focused.select_sources(request(*sources))
    ids = [s.id for s in selected]
    assert {"s4", "s11", "s17"} <= set(ids)
    assert ids.index("s4") < ids.index("s0")
    original = {s.id: s for s in sources}
    for sid in ("s4", "s11", "s17"):
        # Keep distinct staffing numbers, program names and the 60-day exceptions verbatim.
        assert next(s for s in selected if s.id == sid) == original[sid]
    assert "전담인력 2인 이상" in original["s11"].excerpt
    assert "자율 구성" in original["s17"].excerpt
    assert "60일" in original["s11"].excerpt and "60일" in original["s17"].excerpt
    assert "초기" in original["s11"].excerpt and "도약" in original["s17"].excerpt
    if notice_id == 3:
        assert "[문서 전체 텍스트 추출]" in original["s2"].excerpt
        assert "s2" not in ids
    assert len(selected) <= 12
    assert sum(len(s.excerpt.encode()) for s in selected) <= 8000
    input_bytes = focused.CallBudget().reserve(focused.model_messages(selected), focused.Extraction)
    assert input_bytes <= 20000


def test_extraction_restart_does_not_inherit_an_unrelated_eligibility_parent():
    src = Source(
        id="restart", group_label="[상위 구역] 신청자격 및 신청요건\n자)) 대표자 참석",
        excerpt="자)) 대표자 참석\n[표]\n제출 서류\t비고\n사업계획서\t필수\n[/표]\n"
        "[문서 전체 텍스트 추출]\n공고문\n중소기업 성장 지원 사업 안내",
    )
    assert focused.select_sources(request(src)) == []


def test_actual_qualification_sections_on_both_sides_of_extraction_restart_are_preserved():
    first = "□ 신청자격\n업력 3년 이내 중소기업\n"
    second = "□ 신청요건\n전담인력 2인 이상, 단 협약 이후 60일 내 확보 가능\n"
    raw = first + "[PDF 2페이지 시각 판독]\n제출 서류 안내\n" + second
    src = Source(id="combined", group_label="공고", excerpt=raw)
    selected = focused.select_sources(request(src))
    assert len(selected) == 1
    assert first in selected[0].excerpt and second in selected[0].excerpt
    assert "제출 서류 안내" not in selected[0].excerpt
    assert selected[0].id == src.id and selected[0].group_label == src.group_label


def test_noisy_extraction_order_never_merges_different_scopes_numbers_or_exceptions():
    noisy = Source(
        id="visual", group_label="[상위 구역] 가 유형\n□ 신청요건",
        excerpt="□ 신청요건\n기기관관 협협력력\n인력 3인 이상, 단 협약 후 30일 내 확보 가능",
    )
    normal = Source(
        id="body", group_label="[상위 구역] 나 유형\n□ 신청요건",
        excerpt="□ 신청요건\n인력 2인 이상, 단 협약 후 60일 내 확보 가능",
    )
    assert focused.select_sources(request(noisy, normal)) == [normal, noisy]
    assert focused.select_sources(request(noisy)) == [noisy]


def test_explicit_requirements_precede_parent_inherited_and_excluded_sections():
    broad = Source(
        id="broad", group_label="[상위 구역] 신청자격\n기관 의무", excerpt="기업신용 확인",
    )
    excluded = Source(
        id="excluded", group_label="[상위 구역] 신청자격\n□ 신청 제외대상",
        excerpt="부채비율 초과 기업",
    )
    requirement = Source(id="requirement", group_label="□ 신청요건", excerpt="전담인력 2인 필수")
    selected = focused.selection.select(
        request(broad, excluded, requirement), focused.source_fields, focused.CODES, 8000, 1,
    )
    assert selected == [requirement]
    assert focused.selection.prepare(excluded, focused.source_fields)[1] == 2


def test_clear_sentence_contract_fits_the_bounded_serialized_budget():
    assert len(focused.PROMPT.encode()) <= 5500
    src = Source(id="one", excerpt="□ 신청자격\n업력 7년 이내 기업", group_label="신청자격")
    assert focused.CallBudget().reserve(focused.model_messages([src]), focused.Extraction) <= 9000
