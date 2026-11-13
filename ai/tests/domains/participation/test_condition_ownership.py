"""Source selection and nested-output contracts; no paid model or accuracy claims."""
import asyncio
from pathlib import Path

import pytest

from app.domains.participation import focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source


def request(*sources):
    return RewriteRequest(sources=list(sources), criteria=[
        Criterion(code=code, label=code, category="신청 자격") for code in focused.CODES
    ])


def test_public_center_notice_keeps_regional_footnotes_and_staff_exceptions():
    req = RewriteRequest.model_validate_json(
        (Path(__file__).parent / "fixtures/center_ownership_20261113.json").read_text("utf-8")
    )
    selected = focused.select_sources(req)
    assert {s.id for s in selected} == {"s2", "s3", "s4", "s6"}
    regions = next(s for s in selected if s.id == "s2").excerpt
    assert "세종특별자치시, 제주특별자치도, 충청북도" in regions
    assert "부산광역시, 대전광역시, 세종특별자치시" in regions
    assert "사업자등록증 소재지 기준" in regions
    staffing = next(s for s in selected if s.id == "s4").excerpt
    assert "별도의 상근 전담인력(행정매니저)을 두는 센터의 총괄매니저는 상근 겸직 가능" in staffing
    assert "중 하나 이상" in staffing
    # Korean instructions and complete regional/staff evidence fit the unchanged input budget.
    size = focused.CallBudget().reserve(focused.model_messages(selected), focused.Extraction)
    assert size <= focused.MAX_INPUT_BYTES


@pytest.mark.parametrize("heading", ["선정규모", "모집규모", "지원내용"])
def test_an_explicit_applicant_footnote_outweighs_an_unrelated_heading(heading):
    text = (f"□ {heading}\n◦ 지역혁신 센터: 2개\n"
            "* 제주 소재 법인·단체만 신청 가능\n* 단, 이전 예정 기관은 별도 증빙 필요")
    src = Source(id="one", group_label=heading, excerpt=text)
    assert focused.select_sources(request(src)) == [src]
    logistics = Source(
        id="logistics", group_label="접수방법", excerpt="온라인 신청 가능, 이메일 접수 불가",
    )
    assert focused.select_sources(request(logistics)) == []


def test_staff_details_and_exceptions_remain_inside_their_own_parent_in_one_call():
    lines = [
        "총괄매니저는 상근 근무자로 다음 자격 중 하나 이상을 충족해야 함",
        "전문자격 보유 또는 관련 기관 5년 이상 근무",
        "별도 상근 행정매니저를 두면 총괄매니저 상근 겸직 가능",
        "1인 센터는 15석 이상 입주공간 필요",
        "중장년 센터는 10팀 이상 입주공간 필요",
    ]
    src = Source(id="staff", group_label="신청요건", excerpt="\n".join(lines))
    parent = focused.ExtractedCondition(
        source_id="staff", criterion="EXPERIENCE", kind="ELIGIBILITY", scope="공통",
        quote="L1", text="총괄매니저는 상근 근무자로 자격 중 하나 이상을 충족해야 합니다.",
        details=[
            focused.ExtractedDetail(
                source_id="staff", quote="L2",
                text="전문자격을 보유하거나 관련 기관에서 5년 이상 근무해야 합니다."),
            focused.ExtractedDetail(
                source_id="staff", quote="L3",
                text="별도 상근 행정매니저를 두면 총괄매니저의 상근 겸직이 가능합니다."),
        ],
    )
    items = [parent] + [focused.ExtractedCondition(
        source_id="staff", criterion="EXPERIENCE", kind="ELIGIBILITY", scope=program,
        quote=f"L{line}", text=text,
    ) for line, program, text in [
        (4, "1인 센터", "15석 이상을 갖추어야 합니다."),
        (5, "중장년 센터", "10팀 이상 입주공간을 갖추어야 합니다."),
    ]]

    class Model:
        calls = 0

        def with_structured_output(self, *args, **kwargs):
            return self

        async def ainvoke(self, *args, **kwargs):
            self.calls += 1
            return focused.Extraction(items=items, unresolved_source_ids=[])

    model = Model()
    result = asyncio.run(focused.extract(request(src), model))
    assert model.calls == 1
    assert len(result.statements) == 3
    assert [d.supporting_quotes for d in result.statements[0].details] == [[lines[1]], [lines[2]]]
    assert [s.details for s in result.statements[1:]] == [[], []]
    assert [s.scope for s in result.statements] == ["공통", "1인 센터", "중장년 센터"]


@pytest.mark.parametrize("role", ["총괄매니저", "행정매니저", "센터장", "전담인력"])
def test_scope_text_survives_without_a_fixed_staff_dictionary(role):
    assert focused.scope_label(role) == role
    assert focused.scope_label(role + " 양성 지원사업") == role + " 양성 지원사업"
