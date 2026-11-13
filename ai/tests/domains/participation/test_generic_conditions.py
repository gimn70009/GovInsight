"""Generic selection/projection contracts; model answers are controlled, not quality scores."""
import pytest

from app.domains.participation import focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source


def request(*sources):
    return RewriteRequest(sources=list(sources), criteria=[
        Criterion(code=code, label=code, category="신청 자격") for code in focused.CODES
    ])


@pytest.mark.parametrize("title", ["신청자격", "참여자격", "지원대상"])
def test_unlisted_terms_in_existing_qualification_input_are_preserved(title):
    src = Source(id="unlisted", group_label=title,
                 excerpt="접수일 기준 상근 담당자 두 명을 확보해야 합니다.")
    assert focused.source_fields(src) == set()
    assert focused.select_sources(request(src)) == [src]


def test_registration_procedure_does_not_erase_a_cited_residence_requirement():
    src = Source(id="mixed", group_label="신청방법", excerpt=
                 "□ 신청자격\nIRIS 회원가입을 완료해야 하며 "
                 "접수일 기준 서울에 주소를 두어야 합니다.")
    req = request(src)
    selected = focused.select_sources(req)
    extracted = focused.Extraction(items=[focused.ExtractedCondition(
        source_id=src.id, criterion="BUSINESS_FORM", kind="ELIGIBILITY", quote="L2",
        text="신청자는 접수일 기준 서울에 주소를 두어야 합니다.")], unresolved_source_ids=[])
    result = focused.project(extracted, selected, req)
    assert result.statements[0].text == extracted.items[0].text
    assert result.statements[0].supporting_quotes == [src.excerpt.splitlines()[1]]


@pytest.mark.parametrize("role", ["현장책임자", "큐레이터", "작업반장", "총괄매니저"])
def test_names_do_not_override_model_owned_scope_audience_or_nested_details(role):
    src = Source(id="roles", group_label="신청자격", excerpt=
                 f"{role} 1명 필요\n자격증 또는 관련 경력 필요\n대체 근무 허용")
    req = request(src)
    item = focused.ExtractedCondition(
        source_id=src.id, criterion="EXPERIENCE", kind="ELIGIBILITY", quote="L1",
        text=f"{role} 1명을 확보해야 합니다.", scope="공통", audience=None,
        details=[focused.ExtractedDetail(source_id=src.id, quote="L2",
                                        text="자격증 또는 관련 경력이 필요합니다."),
                 focused.ExtractedDetail(
                     source_id=src.id, quote="L3", text="대체 근무를 허용합니다.")])
    result = focused.project(focused.Extraction(items=[item], unresolved_source_ids=[]),
                             focused.select_sources(req), req)
    statement = result.statements[0]
    assert statement.criterion == "EXPERIENCE"
    assert statement.scope == "공통" and statement.audience is None
    assert [d.text for d in statement.details] == [d.text for d in item.details]
    assert [d.supporting_quotes for d in statement.details] == [
        [s] for s in src.excerpt.splitlines()[1:]
    ]
