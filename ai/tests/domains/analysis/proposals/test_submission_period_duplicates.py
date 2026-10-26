import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.analysis.proposals.drafting import LangChainProposalGenerationRunner
from app.domains.analysis.proposals.submission_requirements import (
    collect_submission_requirements,
    reconcile_submission_documents,
)
from app.domains.analysis.schemas.result import PreparationChecklistItem, RequirementSource
from tests.domains.analysis.proposals.test_drafting import ProposalRunner, document, result
from tests.domains.analysis.proposals.test_submission_requirements import FORMS, request

FILE = "[붙임2] 첨단분야 인턴십 지원 사업 신청 안내 및 사업계획서 양식.hwp"
TITLE = "최근 3개 회계연도 결산재무제표 제출"
QUOTE = "참여기업의 최근 3개년도 말 결산 재무제표 (’23~‘25) 원본 또는 사본(원본 대조필)"
ROW = (
    "결산재무제표\npdf\n* 최근 3개년말 결산 재무제표(’23~‘25) 원본 또는 사본(원본 대조필)\n"
    "* 파일명 : 분야_ㅇㅇ대학교_재무제표.pdf/zip\n☞ 기업이 2개 이상인 경우, 압축 파일로 제출"
)
TABLE = (
    "라. 신청서류(온라인 제출)\n순번\n서류명\n파일형식\n비고\n"
    "1\n사업계획서\nhwp\n별첨1 포함하여 제출\n2\n" + ROW + "\n마. 유의사항\n"
)


def fixture():
    req = request(TABLE + FORMS, name=FILE)
    req.content_text = "신청서류: " + QUOTE
    item = PreparationChecklistItem(
        title=TITLE, detail="참여기업은 최근 3개년 결산재무제표 원본 또는 사본을 제출합니다.",
        next_action="회계팀에 결산재무제표를 준비하여 제출 가능한지 확인합니다.",
        stage="APPLICATION", requirement_level="MANDATORY", applies_to="참여기업",
        source=RequirementSource(origin="NOTICE_BODY", section_title="제출서류", excerpt=QUOTE),
    )
    return req, item


@pytest.mark.parametrize("alias", [
    TITLE, "최근 3개년도 말 결산 재무제표 제출", "최근 3년 결산재무제표", "결산재무제표",
])
@pytest.mark.parametrize("reverse", [False, True])
def test_period_alias_is_merged_before_saving_and_keeps_source_conditions(alias, reverse):
    req, item = fixture()
    item.title = alias
    items = [row.as_item() for row in collect_submission_requirements(req)] + [item]
    if reverse:
        items.reverse()
    before = [entry.model_dump() for entry in items]
    merged = reconcile_submission_documents(items, req)
    assert len(merged) == 7
    assert len([entry for entry in merged if entry.stage == "APPLICATION"]) == 5
    financial = [entry for entry in merged if "재무제표" in entry.title]
    assert len(financial) == 1
    assert financial[0].title == "결산재무제표"
    assert financial[0].applies_to == "참여기업"
    for value in ["최근 3개년말", "’23~‘25", "원본 또는 사본", "원본 대조필", "압축 파일로 제출"]:
        assert value in financial[0].detail
    assert QUOTE in financial[0].detail
    assert financial[0].source.origin == "NOTICE_BODY"
    assert financial[0].source.excerpt == QUOTE
    assert FILE in financial[0].detail
    assert reconcile_submission_documents(merged, req) == merged
    assert [entry.model_dump() for entry in items] == before


@pytest.mark.parametrize("case", [
    "other_period", "other_years", "other_condition", "other_role", "other_stage",
    "other_attachment", "no_source", "unknown_qualifier", "other_document",
    "other_level", "additional_condition",
])
def test_distinct_or_unproven_period_requirement_is_preserved(case):
    req, item = fixture()
    if case == "other_period":
        item.title = TITLE.replace("3개", "2개")
        item.source.excerpt = QUOTE.replace("3개", "2개")
    elif case == "other_years":
        item.source.excerpt = QUOTE.replace("23~‘25", "22~‘24")
    elif case == "other_condition":
        item.source.excerpt = QUOTE.replace("원본 또는 사본", "감사보고서 첨부 원본")
    elif case == "other_role":
        item.applies_to = "주관기관"
        item.source.excerpt = QUOTE.replace("참여기업", "주관기관")
        req.attachments[0].extracted_text = req.attachments[0].extracted_text.replace(
            "* 최근", "* 참여기업의 최근",
        )
    elif case == "other_stage":
        item.stage = "AGREEMENT"
    elif case == "other_level":
        item.requirement_level = "CONDITIONAL"
    elif case == "additional_condition":
        item.source.excerpt += "\n* 최초 참여하는 기업만 제출합니다."
    elif case == "other_attachment":
        item.source.origin = "ATTACHMENT"
        item.source.attachment_name = "다른 신청 안내문.hwp"
    elif case == "no_source":
        item.source = None
    elif case == "unknown_qualifier":
        item.title = TITLE.replace(" 제출", "(연결 기준) 제출")
    else:
        item.title = TITLE.replace("결산재무제표", "연결재무제표")
    assert item in reconcile_submission_documents([item], req)


def test_two_explicit_period_rows_survive_source_collection():
    req, _ = fixture()
    req.attachments[0].extracted_text = TABLE.replace(
        "\n마. 유의사항", "\n3\n" + ROW.replace("3개년", "2개년").replace("23~‘25", "24~‘25")
        + "\n마. 유의사항",
    )
    rows = [row for row in collect_submission_requirements(req) if row.title == "결산재무제표"]
    assert len(rows) == 2


@pytest.mark.parametrize("reference", ["붙임", "별첨"])
def test_generic_period_row_does_not_choose_between_numbered_forms(reference):
    req, item = fixture()
    items = [item.model_copy(update={"title": f"최근 3년 결산재무제표({reference}{i} 양식)"})
             for i in [2, 3]]
    merged = reconcile_submission_documents(items, req)
    assert all(entry in merged for entry in items)


def test_generation_reconciles_period_alias_without_an_extra_model_call():
    async def scenario():
        req, item = fixture()
        doc = document()
        doc.content_text = req.content_text
        doc.attachments.extend(req.attachments)
        draft = await ProposalRunner().generate(doc, result())
        draft.preparation.submission_documents.append(item)
        runner = LangChainProposalGenerationRunner.__new__(LangChainProposalGenerationRunner)
        runner._settings = SimpleNamespace(max_text_chars=32000, proposal_timeout_seconds=5)
        runner._draft_model = SimpleNamespace(ainvoke=AsyncMock(return_value=draft))
        generated = await runner.generate(doc, result())
        financial = [entry for entry in generated.preparation.submission_documents
                     if "재무제표" in entry.title]
        assert len(financial) == 1
        assert financial[0].title == "결산재무제표"
        assert financial[0].applies_to == "참여기업"
        assert financial[0].score_basis
        runner._draft_model.ainvoke.assert_awaited_once()

    asyncio.run(scenario())


@pytest.mark.parametrize("first,second", [("3", "2"), ("3", "3")])
def test_short_period_rows_compare_the_period_without_discarding_it(first, second):
    req = request(
        "신청서류\n서류명 파일형식 비고\n"
        f"결산재무제표 pdf 최근 {first}년 결산재무제표 제출\n"
        f"결산재무제표 pdf 최근 {second}년 결산재무제표 제출"
    )
    assert len(collect_submission_requirements(req)) == (1 if first == second else 2)
