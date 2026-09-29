from dataclasses import replace

import pytest

from app.domains.analysis.schemas.result import RequirementSource
from app.domains.report.brief import build_context, fallback_brief, validate_brief
from app.domains.report.submission_documents import (
    SourcePart,
    SubmissionDocument,
    display_submission_documents,
)
from app.domains.report.template import TemplateReportGenerator, _document_block
from tests.domains.report.test_report_brief import output
from tests.domains.report.test_report_checklist_reuse import brief, ready_request
from tests.domains.report.test_report_source_documents import APPLICATION_NAMES
from tests.domains.report.test_report_template import with_document

TITLE = "최근 3개 회계연도 결산재무제표 제출"
FILE = "[붙임2] 첨단분야 인턴십 지원 사업 신청 안내 및 사업계획서 양식.hwp"
ROW = (
    "결산재무제표\npdf\n* 최근 3개년말 결산 재무제표(’23~‘25) 원본 또는 사본(원본 대조필)\n"
    "* 파일명 : 분야_ㅇㅇ대학교_재무제표.pdf/zip\n☞ 기업이 2개 이상인 경우, 압축 파일로 제출"
)
QUOTE = "참여기업의 최근 3개년도 말 결산 재무제표 (’23~‘25) 원본 또는 사본(원본 대조필)"


def source(quote=ROW, name=FILE):
    return RequirementSource(
        origin="ATTACHMENT" if name else "NOTICE_BODY", attachment_name=name,
        section_title="제출서류", excerpt=quote,
    )


@pytest.mark.parametrize("path", ["template", "validated", "fallback"])
@pytest.mark.parametrize("reverse", [False, True])
@pytest.mark.parametrize("alias", [
    TITLE, "최근 3개년도 말 결산 재무제표 제출", "최근 3년 결산재무제표",
])
def test_saved_financial_period_alias_displays_one_document(path, reverse, alias):
    req = ready_request([*APPLICATION_NAMES, alias, "개인정보 동의서"])
    doc = req.documents[0]
    doc.title = "2026년 첨단분야 인턴십 지원사업 4차 공고"
    items = doc.proposal.preparation.submission_documents
    items[1].source = source()
    items[-2].source = source(QUOTE, None)
    if reverse:
        items.reverse()
    before = doc.proposal.model_dump()
    result = brief(req) if path == "validated" else (
        fallback_brief(doc, "제출 안내 생성 실패") if path == "fallback" else None
    )
    body = TemplateReportGenerator().generate(
        req, briefs={doc.detection_id: result} if result else None,
    ).summary
    assert "• 결산재무제표\n" in body
    assert alias not in body
    assert "• 개인정보 동의서" in body
    assert "추가 제출서류" not in body
    assert "추가 제출서류 3종:" in _document_block(doc, compact=True, brief=result)
    assert doc.proposal.model_dump() == before


def pair():
    return [SubmissionDocument("결산재무제표", None, source=source()),
            SubmissionDocument(TITLE, None, source=source(QUOTE, None))]


@pytest.mark.parametrize("case", [
    "missing", "other_period", "other_years", "other_condition", "other_document",
    "other_attachment", "unknown_qualifier", "distinct_forms", "distinct_members",
])
def test_period_alias_needs_matching_requirement_and_unambiguous_identity(case):
    items = pair()
    if case == "missing":
        items[1] = replace(items[1], source=None)
    elif case == "other_period":
        items[1] = replace(items[1], title=TITLE.replace("3개", "2개"))
    elif case == "other_years":
        items[1] = replace(items[1], source=source(QUOTE.replace("23~‘25", "22~‘24"), None))
    elif case == "other_condition":
        items[1] = replace(
            items[1], source=source("최근 3년 결산재무제표 감사확인을 받은 서류에 한함", None),
        )
    elif case == "other_document":
        items[1] = replace(items[1], title=TITLE.replace("결산재무제표", "연결재무제표"))
    elif case == "other_attachment":
        items[1] = replace(items[1], source=source(QUOTE, "다른 사업 안내문.hwp"))
    elif case == "unknown_qualifier":
        items[1] = replace(items[1], title=TITLE.replace(" 제출", "(연결 기준) 제출"))
    else:
        items = [replace(item, form=SourcePart(str(i) + ".pdf", "", "https://example.org/form",
                                             "자료.zip" if case == "distinct_members" else None))
                 for i, item in enumerate(items)]
    assert display_submission_documents(items) == items


def test_generic_document_does_not_bridge_different_period_requirements():
    items = pair()
    items.append(SubmissionDocument(TITLE.replace("3개", "2개"), None,
                                    source=source(QUOTE.replace("3개", "2개"), None)))
    assert display_submission_documents(items) == items


def test_period_in_single_name_is_preserved_without_a_duplicate():
    assert display_submission_documents(pair()[1:]) == pair()[1:]



def test_extracted_period_name_keeps_evidence_for_source_table_deduplication():
    quote = "제출서류: " + QUOTE
    req = with_document(contentText=quote, attachments=[{
        "fileName": FILE, "downloadUrl": "https://example.org/form",
        "extractedText": "라. 신청서류(온라인 제출)\n순번\n서류명\n파일형식\n비고\n1\n" + ROW,
    }])
    doc = req.documents[0]
    alias = "최근 3개년도 말 결산 재무제표"
    result = validate_brief(output(documents=[{
        "title": alias, "condition": None, "form_source_id": None,
        "evidence": {"source_id": "source-0", "quote": quote},
    }]), build_context(doc, 16000), doc)
    assert [item.title for item in result.documents] == ["결산재무제표", alias]
    displayed = display_submission_documents(result.documents)
    assert [item.title for item in displayed] == ["결산재무제표"]


@pytest.mark.parametrize("reverse", [False, True])
def test_period_alias_keeps_download_and_first_position(reverse):
    items = pair()
    form = SourcePart("결산재무제표.pdf", "", "https://example.org/download?id=1&seq=2")
    items[1] = replace(items[1], form=form)
    if reverse:
        items.reverse()
    other = SubmissionDocument("사업자등록증", None)
    items.insert(1, other)
    displayed = display_submission_documents(items)
    assert [item.title for item in displayed] == ["결산재무제표", "사업자등록증"]
    assert displayed[0].form == form
