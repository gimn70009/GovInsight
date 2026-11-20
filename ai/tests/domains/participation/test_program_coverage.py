"""Program coverage and complete request budgets; no paid calls."""
from pathlib import Path

from app.domains.participation import focused, selection
from app.domains.participation.schemas import Criterion, RewriteRequest, Source


def request(sources):
    return RewriteRequest(sources=sources, criteria=[
        Criterion(code=c, label=c, category="신청 자격") for c in focused.CODES
    ])


def test_integrated_notice_keeps_short_program_targets_within_existing_byte_budget():
    req = RewriteRequest.model_validate_json(
        (Path(__file__).parent / "fixtures/integrated-program-targets.json").read_text("utf-8")
    )
    selected = focused.select_sources(req)
    ids = {s.id for s in selected}
    assert {"s7", "s9", "s10", "s12", "s14", "s27", "s30", "s35", "s40"} <= ids
    assert len(selected) > 12
    assert len(selected) <= focused.MAX_SOURCES
    assert sum(len(s.excerpt.encode("utf-8")) for s in selected) <= focused.MAX_SOURCE_BYTES
    size = focused.call_budget().reserve(focused.model_messages(selected), focused.Extraction)
    assert size <= focused.MAX_INPUT_BYTES
    originals = {s.id: s for s in req.sources}
    assert all(s.group_label == originals[s.id].group_label for s in selected)


def test_equal_priority_programs_precede_another_block_of_the_same_program():
    sources = [Source(id=str(i), group_label=f"[상위 구역] {program}\n□ 사업개요",
                      excerpt=f"◦ (지원 대상) {target}")
               for i, (program, target) in enumerate([
                   ("성장사업", "중소 제조기업 중 3년 이상 운영 중인 기업"),
                   ("성장사업", "중소 제조기업 중 5년 이상 운영 중인 기업"),
                   ("도약사업", "국내 중소기업"),
                   ("혁신사업", "국내 중견기업"),
               ])]
    chosen = selection.select(request(sources), focused.source_fields, focused.CODES, 8000, 3)
    assert {selection.program_key(s) for s in chosen} == {"성장사업", "도약사업", "혁신사업"}


def test_long_contexts_do_not_turn_a_small_raw_excerpt_into_an_overbudget_request():
    sources = [Source(id=str(i), group_label=f"[상위 구역] 사업{i}" + "긴사업명" * 400
                      + "\n□ 지원대상",
                      excerpt=f"□ 지원대상\n국내 중소기업 및 분야{i} 제조기업") for i in range(24)]
    selected = focused.select_sources(request(sources))
    assert selected
    assert len(selected) < 24
    size = focused.call_budget().reserve(focused.model_messages(selected), focused.Extraction)
    assert size <= focused.MAX_INPUT_BYTES


def test_contract_disambiguates_programs_roles_and_integrated_advance_notices():
    schema = focused.ExtractedCondition.model_json_schema()["properties"]
    assert "신청 대상·참여 형태" in schema["scope"]["description"]
    assert "표 행·괄호 표기" in schema["audience"]["description"]
    assert "통합공고에 안내된 사업별 신청 대상은 각각 유지" in focused.PROMPT
    assert focused.MAX_OUTPUT_TOKENS == 8192
