"""Public source selection regressions; these do not claim to measure model accuracy."""

import asyncio
import json
from pathlib import Path

import pytest

from app.domains.participation import focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source

FIXTURES = json.loads(
    (Path(__file__).parent / "fixtures/support_vs_eligibility.json").read_text("utf-8")
)


def request(*sources):
    return RewriteRequest(
        sources=list(sources),
        criteria=[Criterion(code=code, label=code, category="신청 자격") for code in focused.CODES],
    )


def source(sid, text, heading):
    return Source(id=sid, excerpt=text, group_label=heading)


@pytest.mark.parametrize(
    "title", ["선정 규모", "모집 규모", "최종 선정", "선정 기준", "지원기간", "지원비율"],
)
def test_support_and_selection_sections_do_not_inherit_parent_eligibility(title):
    src = source(
        "outside", f"□ {title}\n중소기업 14개 내외, 지원기간 6개월, 한도 3.5억원, 지원비율 100%",
        f"[상위 구역] 신청자격 및 신청요건\n[원문 구역] □ {title}",
    )
    assert focused.selection.prepare(src, focused.source_fields) is None
    assert focused.select_sources(request(src)) == []


@pytest.mark.parametrize("title", ["사업 신청 자격", "신청 자격", "신청조건", "지원 대상"])
def test_actual_applicant_requirements_are_kept_before_mixed_support_information(title):
    eligible = source("eligible", f"□ {title}\n국내 중소·중견기업", f"□ {title}")
    mixed = source(
        "mixed", "□ 지원조건\n[표]\n지원대상\t지원기간\n중소기업\t6개월\n[/표]", "□ 지원조건"
    )
    selected = focused.selection.select(
        request(mixed, eligible), focused.source_fields, focused.CODES, 8000, 1,
    )
    assert [row.id for row in selected] == ["eligible"]


@pytest.mark.parametrize("label", ["◦ 지원대상 : 대학", "◦ (지원대상) 대학", "신청자격: 대학"])
def test_explicit_applicant_line_is_preserved_inside_a_support_section(label):
    raw = f"□ 지원내용 및 조건\n{label}\n지원한도 3억원, 정부지원비율 100%"
    src = source("mixed", raw, "□ 지원내용 및 조건")
    selected = focused.select_sources(request(src))
    assert len(selected) == 1
    assert selected[0].excerpt in raw
    assert label in selected[0].excerpt


def test_mixed_table_keeps_all_original_cells_for_the_single_model_call():
    notice = next(row for row in FIXTURES if row["id"] == 5)
    src = Source.model_validate(next(row for row in notice["sources"] if row["id"] == "s44"))
    selected = focused.select_sources(request(src))
    assert len(selected) == 1
    assert selected[0].excerpt == src.excerpt
    assert "전문연구기관\t5개\t6개월\t최대 3.5억원\t100% 내" in selected[0].excerpt
    assert "중소 제조기업 등\t10개\t24개월\t최대 10억원\t75% 내" in selected[0].excerpt
    assert focused.selection.prepare(src, focused.source_fields)[1] == 3
    payload = json.loads(focused.model_messages(selected)[1].content)
    assert "L4: 사전연구 및 제조현장진단\t전문연구기관" in payload["sources"][0]["excerpt"]


def test_direct_and_mixed_sections_in_one_source_both_keep_their_original_spans():
    direct = "□ 신청자격\n국내 중소기업\n"
    mixed = "□ 지원조건\n[표]\n지원대상\t지원비율\n연구기관\t100%\n[/표]\n"
    raw = direct + "□ 문의처\n담당 부서\n" + mixed
    src = source("combined", raw, "공고")
    selected = focused.select_sources(request(src))
    assert len(selected) == 1
    assert direct in selected[0].excerpt and mixed in selected[0].excerpt
    assert "담당 부서" not in selected[0].excerpt


def test_public_operator_notice_prioritizes_applicants_and_omits_selection_quotas():
    notice = next(row for row in FIXTURES if row["id"] == 3)
    sources = [Source.model_validate(row) for row in notice["sources"]]
    selected = focused.select_sources(request(*sources))
    ids = {row.id for row in selected}
    assert {"s0", "s3"} <= ids
    assert not ids & {"s1", "s7", "s9", "s12", "s15"}
    assert any("기업신용등급" in row.excerpt for row in selected)
    assert any("창업기업 지원역량을 보유한 공공기관" in row.excerpt for row in selected)
    assert sum(len(row.excerpt.encode()) for row in selected) <= 8000


def test_public_manufacturing_notice_keeps_real_applicants_and_mixed_target_table():
    notice = next(row for row in FIXTURES if row["id"] == 5)
    sources = [Source.model_validate(row) for row in notice["sources"]]
    selected = focused.select_sources(request(*sources))
    ids = {row.id for row in selected}
    assert {"s7", "s43", "s44"} <= ids
    context = next(row for row in sources if row.id == "s43")
    assert next(row for row in selected if row.id == "s43") == context
    payload = json.loads(focused.model_messages(selected)[1].content)
    model_context = next(row for row in payload["sources"] if row["id"] == "s43")
    assert "기 선정된 전문연구기관이 `28년까지 계속 수행" in model_context["excerpt"]
    assert "제조현장진단 참여기업 중" in model_context["excerpt"]
    assert len(selected) <= 12
    assert "s39" not in ids
    assert not any("총사업비의 30~100%" in row.excerpt for row in selected)
    assert not any("진단비용 80% 지원" in row.excerpt for row in selected)
    assert any("국내 중소·중견기업" in row.excerpt for row in selected)
    assert sum(len(row.excerpt.encode()) for row in selected) <= 8000
    serialized = focused.CallBudget().reserve(focused.model_messages(selected), focused.Extraction)
    assert serialized <= 20000


def test_numeric_applicant_requirements_are_not_filtered_like_funding_numbers():
    raw = "□ 신청자격\n납품실적 14개 이상, 업력 6개월 이상, 매출액 3.5억원 이상인 중소기업"
    src = source("actual", raw, "□ 신청자격")
    assert focused.select_sources(request(src))[0].excerpt == raw


def test_support_only_notice_does_not_start_an_unnecessary_model_call():
    class NoPaidCall:
        def with_structured_output(self, *args, **kwargs):
            raise AssertionError("Support-only input must not call the model")

    src = source("support", "□ 지원조건\n총사업비 30~100% 정부지원", "□ 지원조건")
    result = asyncio.run(focused.extract(request(src), model=NoPaidCall()))
    assert result.status == "READY"
    assert result.statements == []
    assert result.evidence_only_ids == ["support"]


def test_boundary_prompt_and_serialized_input_do_not_increase_the_previous_cost():
    assert len(focused.PROMPT.encode()) <= 1562
    src = source("one", "□ 신청자격\n업력 7년 이내 기업", "신청자격")
    assert focused.CallBudget().reserve(focused.model_messages([src]), focused.Extraction) <= 3106


@pytest.mark.parametrize("body", [
    "기 선정된 기관이 다음 연도까지 계속 수행합니다.",
    "이미 선정된 기관이 해당 과제를 수행합니다.",
    "진단 참여기업 중 해당 요건을 갖춘 기업이 대상입니다.",
    "교육 참여기관에 한하여 신청할 수 있습니다.",
])
def test_explicit_participation_context_survives_support_content_heading(body):
    raw = "□ 지원내용\n" + body + "\n지원한도는 별도 안내합니다."
    src = source("context", raw, "□ 지원내용")
    prepared, priority = focused.selection.prepare(src, focused.source_fields)
    assert prepared == src
    assert priority == 2
    assert focused.select_sources(request(src)) == [src]


@pytest.mark.parametrize("title", ["선정 규모", "모집 규모", "최종 선정"])
def test_participation_words_do_not_promote_selection_quota_sections(title):
    src = source(
        "quota", f"□ {title}\n참여기관 중 총 14개 내외를 선정합니다.",
        f"[상위 구역] 신청자격\n□ {title}",
    )
    assert focused.selection.prepare(src, focused.source_fields) is None
    assert focused.select_sources(request(src)) == []


def test_context_selection_does_not_merge_neighbors_or_infer_shared_business_scope():
    funding = source("funding", "□ 지원내용\n총 사업비 100% 지원", "가 사업\n□ 지원내용")
    context = source(
        "context", "□ 지원내용\n진단 참여기업 중 요건을 갖춘 기업이 신청합니다.",
        "가 사업\n□ 지원내용",
    )
    table = source(
        "table", "□ 지원조건\n[표]\n지원대상\t지원기간\n대학\t6개월\n[/표]",
        "나 사업\n□ 지원조건",
    )
    for sources in ((context, funding, table), (table, funding, context)):
        selected = focused.select_sources(request(*sources))
        assert {row.id for row in selected} == {"context", "table"}
        assert next(row for row in selected if row.id == "context") == context
        assert next(row for row in selected if row.id == "table") == table


def test_support_conditions_keep_actual_eligibility_without_inheriting_parent_priority():
    src = source(
        "ambiguous", "업력 3년 이내 중소기업이어야 합니다.",
        "[상위 구역] 신청자격\n[원문 구역] 지원 조건",
    )
    assert focused.selection.prepare(src, focused.source_fields) == (src, 4)
    assert focused.select_sources(request(src)) == [src]
    direct = source("direct", "□ 신청자격\n신용등급 B 이상인 기업", "□ 신청자격")
    assert {row.id for row in focused.select_sources(request(src, direct))} == {
        "ambiguous", "direct",
    }
    limited = focused.selection.select(
        request(src, direct), focused.source_fields, focused.CODES, 8000, 1,
    )
    assert limited == [direct]
