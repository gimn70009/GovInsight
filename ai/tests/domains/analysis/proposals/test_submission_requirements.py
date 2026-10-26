import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.analysis.proposals.drafting import (
    LangChainProposalGenerationRunner,
    _verified_items,
)
from app.domains.analysis.proposals.submission_requirements import (
    collect_submission_requirements,
    reconcile_submission_documents,
)
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.schemas.result import PreparationChecklistItem, RequirementSource
from tests.domains.analysis.proposals.test_drafting import ProposalRunner, document, result

TABLE = """라. 신청서류(온라인 제출)
순번
서류명
파일형식
비고
1
사업계획서
hwp
별첨1 포함하여 제출
2
결산재무제표
pdf
참여기업의 최근 3년 자료. 2개 이상 기업이 참여하는 경우 zip파일로 제출
마. 유의사항
접수증은 제출 불필요
"""
FORMS = """[별첨 1] 표준 현장실습 학기제 운영계획서
[별첨 2] 표준 현장실습 학기제 협약서 – 협약 시 제출
[별첨 3] 표준 현장실습학기제 평가표 및 출석부
표준 현장실습학기제(Co-op) 운영 계획서
기관명과 실습 기간을 작성합니다.
붙임 서류
1. 사업자 등록증 ▸최초 참여 시 또는 등록 사항의 변경 시 제출
2. 기관소개 자료 ▸최초 참여 시 또는 홍보 목적 등 필요성이 있을 경우 제출
표준 현장실습학기제(Co-op) 협약서
학교와 기관 및 학생이 체결합니다.
표준 현장실습학기제 평가표
본 기관은 운영 완료에 따라
귀 대학에 참여 학생의 평가표 및 출석부를 제출합니다.
"""


def request(text=TABLE + FORMS, *, name="사업 신청 안내 및 양식.hwp"):
    return AnalysisDocumentRequest.model_validate(
        {
            "detectionId": 1,
            "documentId": 2,
            "versionId": 3,
            "changeType": "NEW_DOCUMENT",
            "organizationName": "시험기관",
            "boardName": "공고",
            "title": "시험 지원사업",
            "contentText": "신청 안내는 첨부를 확인합니다.",
            "originalUrl": "https://example.org/notice",
            "attachments": [{"attachmentId": 1, "fileName": name, "extractedText": text}],
        }
    )


def indexed(req):
    return {r.title.replace(" ", ""): r for r in collect_submission_requirements(req)}


@pytest.mark.parametrize("flattened", [False, True])
def test_required_financial_file_survives_optional_online_field(flattened):
    text = "온라인 필수입력 안내\n재무제표\nN\n작성 불필요\n" + TABLE
    if flattened:
        text = text.replace("\n", " ")
    rows = indexed(request(text))
    assert set(rows) == {"사업계획서", "결산재무제표"}
    assert rows["결산재무제표"].level == "MANDATORY"
    assert rows["결산재무제표"].stage == "APPLICATION"
    assert "최근 3년" in rows["결산재무제표"].source.excerpt
    assert "zip" in rows["결산재무제표"].source.excerpt


def test_referenced_form_and_conditional_enclosures_have_separate_obligations():
    rows = indexed(request())
    assert rows["표준현장실습학기제운영계획서"].stage == "APPLICATION"
    assert rows["표준현장실습학기제협약서"].stage == "AGREEMENT"
    assert rows["평가표및출석부"].stage == "REPORTING"
    assert rows["사업자등록증"].level == "CONDITIONAL"
    assert "변경 시" in rows["사업자등록증"].condition
    assert "필요성이 있을 경우" in rows["기관소개자료"].condition


def test_mixed_stages_are_replaced_and_missing_financial_file_is_added():
    req = request()
    items = [
        PreparationChecklistItem(
            title="표준 현장실습학기제 운영 계획서 및 협약서(별첨1~2)",
            detail="운영계획서와 협약서 및 평가표를 모두 협약 시 제출합니다.",
            next_action="담당자가 서류를 준비합니다.",
            stage="AGREEMENT",
            requirement_level="MANDATORY",
            source=RequirementSource(
                origin="ATTACHMENT",
                attachment_name=req.attachments[0].file_name,
                section_title="별첨 목록",
                excerpt=FORMS.split("표준 현장실습학기제(Co-op)")[0].strip(),
            ),
        )
    ]
    reconciled = reconcile_submission_documents(items, req)
    assert len(reconciled) == 7
    assert not any("모두 협약" in item.detail for item in reconciled)
    assert {item.stage for item in reconciled} == {"APPLICATION", "AGREEMENT", "REPORTING"}
    assert all(item.source.excerpt in req.attachments[0].extracted_text for item in reconciled)


@pytest.mark.parametrize(
    "text,name",
    [
        ("[별첨 1] 신청서\n[별첨 2] 확인서", "제출양식.hwp"),
        (TABLE, "참고용 예시.pdf"),
        ("별도 사업 자료\n" + TABLE, "글로벌 기본계획.pdf"),
        ("필수입력\n재무제표 N 작성 불필요", "신청안내.hwp"),
        ("첨부파일 목록: 신청서.pdf, 확인서.hwp", "공고문.pdf"),
    ],
)
def test_template_names_and_reference_material_do_not_prove_submission(text, name):
    assert collect_submission_requirements(request(text, name=name)) == []


@pytest.mark.parametrize(
    "note,level",
    [
        ("해당 기관만 제출", "CONDITIONAL"),
        ("선택 제출", "OPTIONAL"),
        ("제출 불필요", None),
        ("특별한 사정이 있는 경우 제출", None),
    ],
)
def test_conditional_optional_and_ambiguous_rows_are_not_mandatory(note, level):
    text = f"신청서류\n서류명 파일형식 비고\n확인서 pdf {note}\n유의사항"
    rows = collect_submission_requirements(request(text))
    assert ([row.level for row in rows] if level else rows) == ([level] if level else [])


def test_long_row_is_not_cut_before_its_exception():
    text = "신청서류\n서류명 파일형식 비고\n사업계획서 hwp " + "부가 설명 " * 100 + "제출 면제"
    assert collect_submission_requirements(request(text)) == []


def test_archive_form_reference_does_not_cross_inner_document_boundaries():
    text = "[파일: 안내.hwp]\n" + TABLE + "[파일: 다른사업.hwp]\n" + FORMS
    rows = indexed(request(text, name="제출서류.zip"))
    assert "표준현장실습학기제운영계획서" not in rows


def test_post_selection_table_is_not_changed_into_application_stage():
    rows = indexed(request(TABLE.replace("신청서류(온라인 제출)", "선정 후 제출서류")))
    assert {row.stage for row in rows.values()} == {"POST_SELECTION"}


def test_exceeding_api_capacity_does_not_silently_drop_obligations():
    rows = "\n".join(f"항목{i}확인서 pdf 제출" for i in range(28))
    req = request("신청서류\n서류명 파일형식 비고\n" + rows)
    with pytest.raises(ValueError, match="전체 목록"):
        reconcile_submission_documents([], req)


def test_punctuation_only_citation_does_not_pass_evidence_validation():
    item = PreparationChecklistItem(
        title="임의 신청서",
        detail="검증용으로 만든 원문 없는 항목입니다.",
        next_action="담당자가 확인합니다.",
        source=RequirementSource(origin="NOTICE_BODY", section_title="제출 안내", excerpt="....."),
    )
    assert _verified_items([item], "공고 본문", {}) == []


def test_generation_reconciles_documents_with_one_model_call():
    async def scenario():
        doc = document()
        doc.attachments[0].extracted_text += "\n" + TABLE + FORMS
        draft = await ProposalRunner().generate(doc, result())
        runner = LangChainProposalGenerationRunner.__new__(LangChainProposalGenerationRunner)
        runner._settings = SimpleNamespace(max_text_chars=32000, proposal_timeout_seconds=5)
        runner._draft_model = SimpleNamespace(ainvoke=AsyncMock(return_value=draft))
        generated = await runner.generate(doc, result())
        titles = {
            item.title.replace(" ", "") for item in generated.preparation.submission_documents
        }
        assert {"사업계획서", "결산재무제표", "사업자등록증"} <= titles
        runner._draft_model.ainvoke.assert_awaited_once()

    asyncio.run(scenario())


@pytest.mark.parametrize("title", [
    "사업계획서(붙임2 양식)", "사업 계획서 (붙임 제2호 서식)",
    "사업계획서（붙임 2 양식）", "사업계획서(양식)",
])
def test_source_plan_replaces_model_alias_with_form_reference(title):
    req = request(TABLE)
    item = PreparationChecklistItem(
        title=title, detail="붙임2 양식으로 사업계획서를 작성합니다.",
        next_action="사업계획서를 준비합니다.", stage="APPLICATION",
        requirement_level="MANDATORY",
        source=RequirementSource(
            origin="ATTACHMENT", attachment_name=req.attachments[0].file_name,
            section_title="제출서류", excerpt="사업계획서\nhwp\n별첨1 포함하여 제출",
        ),
    )
    assert [row.title for row in reconcile_submission_documents([item], req)] == [
        "사업계획서", "결산재무제표",
    ]


@pytest.mark.parametrize("title", ["사업계획서(개인)", "사업계획서(단체)", "영문 사업계획서"])
def test_source_plan_does_not_replace_model_item_with_identity_qualifier(title):
    item = PreparationChecklistItem(
        title=title, detail="구분된 대상의 서류입니다.", next_action="서류를 준비합니다.",
        stage="APPLICATION", requirement_level="MANDATORY",
    )
    assert title in [row.title for row in reconcile_submission_documents([item], request(TABLE))]


@pytest.mark.parametrize("reference", ["붙임", "별첨", "서식"])
def test_generic_source_name_does_not_choose_between_numbered_model_forms(reference):
    titles = [f"사업계획서({reference}2 양식)", f"사업계획서({reference}3 양식)"]
    items = [PreparationChecklistItem(
        title=title, detail="번호가 다른 별도 양식입니다.", next_action="해당 양식을 확인합니다.",
        stage="APPLICATION", requirement_level="MANDATORY",
    ) for title in titles]
    reconciled = reconcile_submission_documents(items, request(TABLE))
    assert all(title in [row.title for row in reconciled] for title in titles)


@pytest.mark.parametrize("same_source", [True, False])
def test_college_form_alias_requires_matching_source_row(same_source):
    req = request(TABLE.replace("별첨1 포함하여 제출", "붙임2 참고. 별첨1 포함하여 제출"))
    title = "사업계획서(대학 제출용, 붙임2 양식)"
    item = PreparationChecklistItem(
        title=title, detail="대학이 붙임2 사업계획서를 업로드합니다.",
        next_action="서류를 준비합니다.", stage="APPLICATION", requirement_level="MANDATORY",
        source=RequirementSource(
            origin="ATTACHMENT", attachment_name=req.attachments[0].file_name,
            section_title="신청서류",
            excerpt="사업계획서 hwp 붙임2 참고. 별첨1 포함하여 제출" if same_source
            else "기업은 별도로 작성한 사업계획서를 해당 대학에 제출합니다.",
        ),
    )
    titles = [row.title for row in reconcile_submission_documents([item], req)]
    assert (title not in titles) is same_source
    assert "사업계획서" in titles
