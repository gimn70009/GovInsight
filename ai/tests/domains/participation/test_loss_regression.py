import json
from pathlib import Path

import pytest

from app.domains.participation import completeness, focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source

FIXTURES = json.loads(
    (Path(__file__).parent / "fixtures/eligibility-loss-20261120.json").read_text("utf-8")
)
EXPECTED = {
    4: [
        "모두 신청 가능",
        "1개 유형만 수행 가능",
        "전담조직 사무실 소재지",
        "전문성이 있는 인력",
        "자율 구성",
    ],
    6: [
        "성공 판정",
        "하나를 선택",
        "데이터 수집·검증",
        "우선 지원받은 후",
        "주관기관과 컨소시엄 필수",
        "전사적 AX지원",
        "단순 범용 공정",
        "선도형 스마트공장 선정기업",
        "PoC 시범연구 종료평가",
        "기업(필수)",
        "교육·심의기관",
        "기 선정된 전문연구기관이 `28년까지 계속 수행",
    ],
    7: [
        "1가지 유형을 선택",
        "연접한",
        "협력도시는 1곳 이내",
        "추진단장",
        "TIPS 운영사",
        "정규직을 최소 3인",
        "경력 5년 이상",
        "총괄책임자 제외",
        "각 1인 이상",
        "중앙정부 협의 필요",
    ],
}


@pytest.mark.parametrize("fixture", FIXTURES, ids=lambda f: str(f["version"]))
def test_original_missing_conditions_reach_the_model_within_explicit_bounds(fixture):
    request = RewriteRequest.model_validate(fixture["request"])
    selected = focused.select_sources(request)
    text = "\n".join(s.excerpt for s in selected)
    for phrase in EXPECTED[fixture["version"]]:
        assert phrase in text, phrase
    assert len(selected) <= focused.MAX_SOURCES
    assert sum(len(s.excerpt.encode()) for s in selected) <= focused.MAX_SOURCE_BYTES
    assert (
        focused.call_budget().reserve(focused.model_messages(selected), focused.Extraction)
        <= focused.MAX_INPUT_BYTES
    )
    originals = {s.id: s for s in request.sources}
    for source in selected:
        for span in source.excerpt.split("\n[다음 원문 구간]\n"):
            assert span in originals[source.id].excerpt


def project(source, items):
    request = RewriteRequest(
        sources=[source], criteria=[Criterion(code="EXPERIENCE", label="인력", category="자격")]
    )
    return focused.project(
        focused.Extraction(items=items, unresolved_source_ids=[]), [source], request
    )


def item(quote, text, details=None):
    return focused.ExtractedCondition(
        source_id="s",
        criterion="EXPERIENCE",
        kind="ELIGIBILITY",
        quote=quote,
        text=text,
        details=details or [],
    )


@pytest.mark.parametrize(
    "header,row",
    [
        ("구분\t구분\t주요 내용", "인력\t요건\t전담인력 7인 중 경력 5년 이상 최소 3인 확보"),
        ("구분\t자격 기준\t인원 수\t요건", "전담인력\t사업 참여율 100%\t2인 이상\t필수"),
    ],
)
def test_three_and_four_column_tables_check_each_required_row(header, row):
    source = Source(id="s", group_label="신청요건", excerpt=f"[표]\n{header}\n{row}\n[/표]")
    assert [r[0] for r in completeness.qualification_rows(source)] == ["L3"]
    result = project(source, [item("L3", "전담인력을 확보해야 합니다.")])
    assert result.diagnostics["APPLICANT_ROW_UNCOVERED"] == 1
    assert result.statements[0].review_required


def test_attached_detail_can_cover_a_row_but_a_total_alone_cannot():
    row = "추진단\t요건\t15개 이상(기본 5개 외 10개 과업별 1개 이상)"
    source = Source(
        id="s", group_label="신청요건", excerpt=f"[표]\n구분\t구분\t주요 내용\n{row}\n[/표]"
    )
    text = "추진단은 15개 이상으로 구성해야 합니다."
    assert project(source, [item("L3", text)]).statements[0].review_required
    detail = focused.ExtractedDetail(
        source_id="s", quote="L3", text="기본 5개 외 10개 과업별로 1개 이상을 확보해야 합니다."
    )
    assert not project(source, [item("L3", text, [detail])]).statements[0].review_required


def test_role_paragraphs_are_checked_separately_from_supported_companies():
    source = Source(
        id="s",
        group_label="지원내용",
        excerpt=("◦ 대상 기관\n- (확인기관) 사업 추진 역량을 보유한 기관\n"
                 "- (교육기관) 교육과 심의 역량을 보유한 기관\n◦ 지원내용\n- 기관 운영비 지원"),
    )
    assert [r[0] for r in completeness.qualification_rows(source)] == ["L2", "L3"]
    result = project(source, [item("L2", "확인기관은 사업 추진 역량을 보유해야 합니다.")])
    assert result.diagnostics["APPLICANT_ROW_UNCOVERED"] == 1


def test_restart_cannot_inherit_administrative_scope_in_model_context():
    source = Source(
        id="s",
        group_label="[상위 구역] (제출서류) 1)사 사업\n□ 신청요건",
        excerpt="전담인력 7명 이상",
    )
    assert "parent" not in focused.source_context(source)
