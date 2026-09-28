import pytest

from app.domains.analysis.proposals.submission_requirements import (
    collect_submission_requirements,
    reconcile_submission_documents,
)
from app.domains.analysis.schemas.result import PreparationChecklistItem, RequirementSource
from tests.domains.analysis.proposals.test_submission_requirements import request

LEVEL_TABLE = """□ 제출서류
순번 제출서류 파일형태 필수여부 대상
1 사업계획서 HWP 필수 주관기관
2 수행기관 대표의 참여의사 확인서 PDF 필수 주관·참여기관
3 민간부담금 현금·현물 납입확약서 PDF 해당시 해당기관
4 신청자격 적정성 확인서 PDF 필수 주관·참여기관
6. 기타 유의사항
"""
RECIPIENT_TABLE = """2. (공동)제출 서류
번호 서류 유형 파일 형태 제출 대상기관 비고
1 국문연구개발계획서 hwp 주관연구개발기관이 대표로 제출 - [첨부] 관련서식 활용
2 국내외 연구개발기관 간 협정서 PDF 주관연구개발기관이 대표로 제출
- 국내외 全 연구개발기관간 협정서(MoU), 참여확인서(LOI),
국내외 全 연구개발기관 책임자의 서명이 날인된 영문연구개발계획서 中 택 1
3 사업자등록증 PDF 영리기관 (주관+공동연구개발기관 모두 제출)
- 모든 기업 제출(비영리는 면제)
4 주관 및 공동연구개발기관의 기업부설연구소 인정서 PDF 영리기관
- 기업이 연구개발기관인 경우 해당
5 주관 및 공동연구개발기관의 신청자격 적정성 확인서 PDF 주관+공동연구개발기관 모두 제출
- [첨부] 관련서식 활용
6 주관 및 공동연구개발기관의 회계감사보고서 또는 재무제표 PDF 영리기관
- 표지, 재무상태표, 손익계산서를 PDF로 스캔한 파일
* 비영리와 상장사(거래소·코스닥) 미제출, 그 외 기업은 제출(최근 3년 결산 자료)
VI 문의처
"""


def item(title, excerpt="신청 안내에 따른 서류", **kwargs):
    return PreparationChecklistItem(
        title=title,
        detail="기존 모델이 생성한 제출 안내입니다.",
        next_action="확인합니다.",
        source=RequirementSource(origin="NOTICE_BODY", section_title="신청 서류", excerpt=excerpt),
        **kwargs,
    )


@pytest.mark.parametrize("flatten", [False, True])
def test_required_target_columns_restore_missing_plan_and_conditional_items(flatten):
    text = LEVEL_TABLE.replace("\n", " ") if flatten else LEVEL_TABLE
    rows = collect_submission_requirements(request(text))
    assert len(rows) == 4
    assert rows[0].title == "사업계획서"
    assert rows[0].level == "MANDATORY"
    assert rows[2].level == "CONDITIONAL"
    assert rows[2].condition == "해당기관 (해당 시)"
    assert all(r.stage == "APPLICATION" for r in rows)


def test_numbered_form_annotations_do_not_leak_into_previous_row():
    text = (
        LEVEL_TABLE.replace("1 사업", "1 (서식1) 사업")
        .replace("2 수행", "2 (서식2) 수행")
        .replace("6. 기타 유의사항", "※ 1~4 서류는 각각 별도 파일로 제출")
    )
    rows = collect_submission_requirements(request(LEVEL_TABLE + text))
    assert len(rows) == 4
    assert rows[0].condition == "주관기관"
    assert "서식2" not in rows[0].source.excerpt


@pytest.mark.parametrize("flatten", [False, True])
def test_recipient_table_preserves_exemptions_and_alternatives(flatten):
    text = RECIPIENT_TABLE.replace("\n", "") if flatten else RECIPIENT_TABLE
    rows = collect_submission_requirements(request(text))
    assert len(rows) == 6
    assert rows[0].level == "MANDATORY"
    assert rows[1].level == "MANDATORY"
    assert "책임자의 서명이 날인된" in rows[1].condition
    assert "택 1" in rows[1].condition
    assert rows[2].level == rows[3].level == rows[5].level == "CONDITIONAL"
    assert "비영리는 면제" in rows[2].condition
    assert "상장사" in rows[5].condition
    assert "또는 면제" not in rows[3].title


def test_long_complete_row_preserves_late_exemption_and_bounded_citation():
    text = RECIPIENT_TABLE.replace(
        "- 표지, 재무상태표, 손익계산서를 PDF로 스캔한 파일",
        "- " + "회계자료의 작성 방법에 관한 설명입니다. " * 11,
    )
    row = collect_submission_requirements(request(text))[-1]
    assert row.level == "CONDITIONAL"
    assert "상장사" in row.as_item().detail
    assert "상장사" in row.source.excerpt
    assert len(row.source.excerpt) <= 300
    assert len(row.as_item().detail) <= 500
    assert " ".join(row.source.excerpt.split()) in " ".join(text.split())


def test_oversized_row_is_not_promoted_to_mandatory():
    text = RECIPIENT_TABLE.replace(
        "- 표지, 재무상태표, 손익계산서를 PDF로 스캔한 파일", "- " + "긴 설명 " * 100
    )
    rows = collect_submission_requirements(request(text))
    assert not any("재무제표" in row.title for row in rows)


def test_known_template_aliases_and_wrong_levels_are_reconciled_once():
    before = [
        item("국문연구개발계획서 (양자 및 다자 공동펀딩형)", requirement_level="CONDITIONAL"),
        item("국문연구개발계획서"),
        item("기업부설연구소 인정서(또는 면제 사유 문서)"),
        item("주관 및 공동연구개발기관의 신청자격", requirement_level="CONDITIONAL"),
        item("회계감사보고서 또는 재무제표 제출 요건"),
    ]
    doc = request(RECIPIENT_TABLE)
    after = reconcile_submission_documents(before, doc)
    assert len(after) == 6
    assert after[0].requirement_level == "MANDATORY"
    assert after[-1].requirement_level == "CONDITIONAL"
    assert after == reconcile_submission_documents(after, doc)


def test_identity_qualifiers_and_different_roles_are_preserved():
    before = [item("사업계획서(지자체)", excerpt="지자체는 사업계획서를 제출합니다.")]
    after = reconcile_submission_documents(before, request(LEVEL_TABLE))
    assert before[0] in after


def test_shorthand_joint_roles_match_full_role_names():
    before = [item("수행기관 대표의 참여의사 확인서", excerpt="주관기관은 확인서를 제출합니다.")]
    after = reconcile_submission_documents(before, request(LEVEL_TABLE))
    assert len(after) == 4


def test_outsourcing_plan_is_conditional_even_if_model_said_mandatory():
    text = "모든 외주용역은 사업계획서 제출 시 외주용역 활용계획서 제출 필수"
    before = [item("외주용역 활용계획서 제출 의무 확인", excerpt=text)]
    after = reconcile_submission_documents(before, request(text))
    assert len(after) == 1
    assert after[0].requirement_level == "CONDITIONAL"
    assert after[0].applies_to == "외주용역을 진행하는 경우"


@pytest.mark.parametrize(
    "heading,stage",
    [
        ("□ 협약 시 제출서류", "AGREEMENT"),
        ("□ 선정 후 제출서류", "POST_SELECTION"),
        ("□ 사업 완료 후 제출서류", "REPORTING"),
    ],
)
def test_other_stages_are_not_application_documents(heading, stage):
    rows = collect_submission_requirements(request(LEVEL_TABLE.replace("□ 제출서류", heading)))
    assert rows and all(r.stage == stage for r in rows)


@pytest.mark.parametrize("replacement", ["□ 참고용 제출서류", "□ 제출서류 예시"])
def test_example_tables_are_not_obligations(replacement):
    assert (
        collect_submission_requirements(request(LEVEL_TABLE.replace("□ 제출서류", replacement)))
        == []
    )


def test_separate_zip_members_cannot_supply_table_columns_and_rows():
    text = "[파일: 안내.hwp]\n□ 제출서류\n순번 제출서류 파일형태 필수여부 대상\n"
    text += "[파일: 서식.hwp]\n1 사업계획서 HWP 필수 주관기관\n"
    assert collect_submission_requirements(request(text, name="첨부.zip")) == []


@pytest.mark.parametrize(
    "parent,stage",
    [
        ("5. 협약 시 제출", "AGREEMENT"),
        ("V. 선정 후 준비자료", "POST_SELECTION"),
    ],
)
def test_parent_heading_preserves_stage(parent, stage):
    rows = collect_submission_requirements(request(parent + "\n" + LEVEL_TABLE))
    assert rows and all(r.stage == stage for r in rows)


def test_reference_parent_does_not_turn_into_required_table():
    assert collect_submission_requirements(request("5. 참고자료\n" + LEVEL_TABLE)) == []


def test_unrecognized_following_row_cannot_become_a_recipient():
    text = "□ 제출서류\n순번 제출서류 파일형태 필수여부 대상\n"
    text += "1 사업계획서 HWP 필수 주관기관2 증빙자료 JPEG 필수 참여기관"
    assert collect_submission_requirements(request(text)) == []


@pytest.mark.parametrize("penalty", ["미제출 시 해당 기관은 탈락", "미제출 기관은 접수 불가"])
def test_penalty_for_non_submission_is_not_an_exemption(penalty):
    text = "□ 제출서류\n번호 서류 유형 파일 형태 제출 대상기관 비고\n"
    text += "1 사업계획서 HWP 주관기관 - " + penalty
    rows = collect_submission_requirements(request(text))
    assert len(rows) == 1
    assert rows[0].level == "MANDATORY"


def test_oversized_target_is_not_truncated_into_a_false_condition():
    text = "□ 제출서류\n순번 제출서류 파일형태 필수여부 대상\n"
    text += "1 사업계획서 HWP 해당시 " + "주관기관 " * 40
    assert collect_submission_requirements(request(text)) == []
