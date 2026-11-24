"""Saved-label regressions and mocked extraction; no external calls."""

import pytest

from app.domains.participation import context_labels, focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source


@pytest.mark.parametrize("label", [
    "사업 개요 - 지원분야", "사업 개요 - 수행내용", "사업개요>지원분야",
    "사업개요:지원분야", "사업개요/지원분야", "사업개요·지원분야", "자격 확인서",
    "신청 자격 적정성 확인서", "총괄주관연구개발기관 모집 공고 / 자격 확인서",
])
def test_section_paths_and_recruitment_forms_are_not_programs(label):
    assert focused.scope_label(label) is None


@pytest.mark.parametrize(("label", "expected"), [
    ("성장사업 · 사업 개요 - 지원분야 / 도약사업", "성장사업 / 도약사업"),
    ("연구기관 역량강화 지원사업", "연구기관 역량강화 지원사업"),
    ("사업 개요 작성 지원사업", "사업 개요 작성 지원사업"),
    ("소재·부품·장비", "소재·부품·장비"),
    ("일반 유형 / DX 유형", "일반 유형 / DX 유형"),
    ("A-B형", "A-B형"),
])
def test_real_programs_and_types_remain_distinct(label, expected):
    assert focused.scope_label(label) == expected


@pytest.mark.parametrize(("label", "quote", "expected"), [
    ("신청기관(기관 및 개인)", "참여제한 중인 자(기관 및 개인)는 신청 불가", "기관 및 개인"),
    ("기업 신청자", "기업의 경우 부도상태인가?", "기업"),
    ("연구책임자 및 참여연구자", "연구책임자로 최대 3개, 참여연구자로 최대 5개를 초과하였는가?",
     "연구책임자 및 참여연구자"),
    ("주관·공동연구개발기관 및 연구책임자",
     "주관연구개발기관, 공동연구개발기관, 연구책임자가 의무를 불이행한 경우",
     "주관·공동연구개발기관 및 연구책임자"),
    ("협·단체·연구기관·기업지원기관", "협·단체, 연구기관(국공립·민간), 기업지원기관이 신청 가능",
     "협·단체·연구기관·기업지원기관"),
    ("기업지원기관", "연구기관은 연구 실적을 보유해야 합니다.", None),
    ("연구기관(주관기관)", "연구기관은 연구 실적을 보유해야 합니다.", "연구기관"),
    ("총괄주관연구개발기관", "반도체와 바이오 분야를 지원합니다.", None),
])
def test_audience_uses_only_its_own_citation(label, quote, expected):
    assert context_labels.audience_label(label, quote) == expected


def test_bad_labels_do_not_remove_a_grounded_condition_or_borrow_another_row():
    quote = "연구기관은 관련 분야의 연구 실적을 보유해야 합니다."
    source = Source(id="one", group_label="사업 개요 - 지원분야",
                    excerpt=quote + "\n기업지원기관은 전문인력을 확보해야 합니다.")
    request = RewriteRequest(sources=[source], criteria=[
        Criterion(code=code, label=code, category="신청 자격") for code in focused.CODES
    ])
    extraction = focused.Extraction(items=[focused.ExtractedCondition(
        source_id="one", criterion="BUSINESS_FORM", kind="ELIGIBILITY", quote="L1", text=quote,
        scope="사업 개요 - 지원분야", audience="기업지원기관",
    )], unresolved_source_ids=[])
    result = focused.project(extraction, [source], request)
    assert len(result.statements) == 1
    statement = result.statements[0]
    assert statement.scope is None and statement.audience is None
    assert statement.text == quote and statement.supporting_quotes == [quote]
