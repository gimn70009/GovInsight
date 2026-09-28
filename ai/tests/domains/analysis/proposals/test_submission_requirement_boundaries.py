from app.domains.analysis.proposals.submission_requirements import (
    collect_submission_requirements,
    reconcile_submission_documents,
)
from app.domains.analysis.schemas.result import PreparationChecklistItem, RequirementSource
from tests.domains.analysis.proposals.test_submission_requirements import FORMS, TABLE, request


def test_same_document_required_from_two_roles_is_not_merged():
    req = request(
        "신청서류\n서류명 파일형식 비고\n"
        "사업계획서 hwp 주관기관이 제출\n사업계획서 hwp 공동기관이 제출"
    )
    rows = collect_submission_requirements(req)
    assert len(rows) == 2
    assert "주관기관" in rows[0].source.excerpt
    assert "공동기관" in rows[1].source.excerpt


def test_optional_row_is_not_promoted_to_required_by_filename():
    req = request("신청서류\n서류명 파일형식 비고\n사업계획서 hwp 선택 제출")
    generated = PreparationChecklistItem(
        title="사업계획서",
        detail="사업계획서를 필수로 제출해야 합니다.",
        next_action="담당자가 문서를 준비합니다.",
        requirement_level="MANDATORY",
    )
    result = reconcile_submission_documents([generated], req)
    assert len(result) == 1
    assert result[0].requirement_level == "OPTIONAL"
    assert "필수로" not in result[0].detail


def test_same_name_with_two_conditions_is_preserved():
    req = request(
        "신청서류\n서류명 파일형식 비고\n확인서 pdf 최초 참여 시 제출\n확인서 pdf 변경 시 제출"
    )
    rows = collect_submission_requirements(req)
    assert len(rows) == 2
    assert all(row.level == "CONDITIONAL" for row in rows)
    assert rows[0].condition != rows[1].condition


def test_application_heading_takes_precedence_over_preceding_agreement_sentence():
    req = request("협약 시 서류는 추후 안내합니다.\n" + TABLE)
    assert {row.stage for row in collect_submission_requirements(req)} == {"APPLICATION"}


def test_reference_conflict_is_not_resolved_by_guessing():
    req = request(
        TABLE
        + FORMS.replace(
            "[별첨 1] 표준 현장실습 학기제 운영계획서",
            "[별첨 1] 표준 현장실습 학기제 운영계획서 – 협약 시 제출",
        )
    )
    assert not any("운영계획서" in row.title for row in collect_submission_requirements(req))


def test_duplicate_reference_numbers_are_not_guessed():
    req = request(TABLE + FORMS + "\n[별첨 1] 다른사업 신청서\n")
    assert not any("운영계획서" in row.title for row in collect_submission_requirements(req))


def test_similar_but_different_document_is_not_deleted():
    req = request(TABLE)
    item = PreparationChecklistItem(
        title="해외사업계획서",
        detail="해외 기관에는 별도 계획서를 제출합니다.",
        next_action="담당자가 해외 기관의 제출 안내를 확인합니다.",
        source=RequirementSource(
            origin="NOTICE_BODY",
            section_title="해외 신청",
            excerpt="해외사업계획서를 별도로 제출합니다.",
        ),
    )
    result = reconcile_submission_documents([item], req)
    assert "해외사업계획서" in {row.title for row in result}


def test_partly_matched_combination_does_not_lose_the_other_document():
    item = PreparationChecklistItem(
        title="사업계획서 및 동의서",
        detail="두 서류를 접수 시 제출해야 합니다.",
        next_action="담당자가 두 서류를 작성합니다.",
        source=RequirementSource(
            origin="NOTICE_BODY",
            section_title="신청 서류",
            excerpt="사업계획서 및 동의서를 제출합니다.",
        ),
    )
    result = reconcile_submission_documents([item], request(TABLE))
    assert any("동의서" in row.title for row in result)


def test_referenced_form_explains_it_is_part_of_parent_document():
    result = reconcile_submission_documents([], request())
    operating = next(row for row in result if "운영계획서" in row.title)
    assert "사업계획서에 포함" in operating.detail


def test_reconciliation_is_idempotent():
    req = request()
    first = reconcile_submission_documents([], req)
    second = reconcile_submission_documents(first, req)
    assert [row.model_dump() for row in first] == [row.model_dump() for row in second]


def test_explicit_different_role_in_model_source_is_preserved():
    req = request("신청서류\n서류명 파일형식 비고\n사업계획서 hwp 주관기관이 제출")
    item = PreparationChecklistItem(
        title="사업계획서",
        detail="공동기관이 별도로 계획서를 제출합니다.",
        next_action="담당자가 공동기관의 서류를 준비합니다.",
        source=RequirementSource(
            origin="NOTICE_BODY",
            section_title="공동기관 안내",
            excerpt="공동기관은 사업계획서를 별도로 제출합니다.",
        ),
    )
    result = reconcile_submission_documents([item], req)
    assert len(result) == 2
    assert item in result


def test_duplicate_roles_do_not_hide_unmatched_document_in_combination():
    req = request(
        "신청서류\n서류명 파일형식 비고\n"
        "사업계획서 hwp 주관기관이 제출\n사업계획서 hwp 공동기관이 제출"
    )
    item = PreparationChecklistItem(
        title="사업계획서 및 동의서",
        detail="두 서류를 접수 시 제출해야 합니다.",
        next_action="담당자가 두 서류를 작성합니다.",
        source=RequirementSource(
            origin="NOTICE_BODY",
            section_title="신청 안내",
            excerpt="사업계획서 및 동의서를 제출합니다.",
        ),
    )
    result = reconcile_submission_documents([item], req)
    assert any("동의서" in row.title for row in result)
