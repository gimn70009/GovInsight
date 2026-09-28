from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.report import enrichment
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_brief import output, run, validate
from tests.domains.report.test_report_template import with_document


def row(quote="1. 신청서 1부", title="신청서", source_id="source-0", condition=None):
    return {
        "title": title,
        "condition": condition,
        "evidence": {"source_id": source_id, "quote": quote},
        "form_source_id": None,
    }


@pytest.mark.parametrize(
    "source",
    [
        "제출서류\n서류명 부수\n1. 신청서 1부\n6. 문의처\n담당 부서",
        "□ 제출 서류\n서류명 부수\n1. 신청서 1부",
        "나. 제출서류 No서류명부수1. 신청서 1부\n다. 유의사항",
        "◦ 제출서류 구분 서류명 필수 제출 1. 신청서 1부 6. 문의처 담당 부서",
        "○ (구비서류)\n1. 신청서 1부",
        "신청서류 목록\r\n1. 신청서 1부",
        "제 출 서 류：\n1. 신청서 1부",
        "3. 제출서류\n1. 신청서 1부\n2. 정관 사본 1부",
        "제출서류\n1. 신청서 1부 (붙임 1 참고)",
    ],
)
def test_document_row_inherits_an_explicit_heading_in_the_same_section(source):
    req = with_document(contentText=source)
    brief = validate(output(documents=[row()]), req)
    assert [item.title for item in brief.documents] == ["신청서"]
    assert brief.note is None


@pytest.mark.parametrize(
    "boundary",
    [
        "6. 문의처",
        "□ 참고자료",
        "◦ 다른 표 안내",
        "가. 평가기준",
        "평가항목 및 배점",
        "[일부 원문 생략: 누락 부분은 확인되지 않음]",
        "[파일: 다른 안내문.pdf]",
        "<별첨> 작성 예시",
    ],
)
def test_heading_cannot_be_borrowed_across_section_or_omission_boundaries(boundary):
    source = f"□ 제출서류\n정관 사본 1부\n{boundary}\n1. 신청서 1부"
    assert validate(output(documents=[row()]), with_document(contentText=source)).documents == []


@pytest.mark.parametrize(
    "source",
    [
        "□ 선정 후 준비사항\n◦ 제출서류\n1. 신청서 1부",
        "□ 협약 시 준비사항\n제출서류 목록\n1. 신청서 1부",
        "□ 협약 체결 후 준비사항\n제출서류 목록\n1. 신청서 1부",
        "□ 참고\n제출서류 목록\n1. 신청서 1부",
        "□ 참고자료\n제출서류 목록\n1. 신청서 1부",
        "제출서류 작성 예시입니다.\n1. 신청서 1부",
        "제출서류 목록을 참고하세요.\n1. 신청서 1부",
        "제출서류\n1. 신청서 1부 (제출 불필요)",
        "제출서류\n1. 신청서 1부 (제출 면제)",
        "제출서류\n1. 신청서 1부 (제출 생략)",
        "제출서류\n1. 신청서 1부 (참고용)",
        "제출서류\n1. 신청서 1부 (선정 후 제출)",
        "제출서류\n1. 신청서 1부 (협약 후 제출)",
        "제출서류\n1. 신청서 1부 (제출하지 않음)",
        "1. 신청서 1부\n□ 제출서류\n정관 사본",
        "□ 제출서류\n" + "관련 설명 " * 500 + "\n1. 신청서 1부",
    ],
)
def test_reference_later_stage_exempt_or_ambiguous_context_is_not_submission_proof(source):
    assert validate(output(documents=[row()]), with_document(contentText=source)).documents == []


@pytest.mark.parametrize("name", ["참고자료.pdf", "신청서 양식.hwp"])
def test_form_or_reference_file_does_not_gain_submission_proof_from_a_heading(name):
    req = with_document(attachments=[{
        "fileName": name,
        "downloadUrl": "https://example.go.kr/file",
        "extractedText": "제출서류\n1. 신청서 1부",
    }])
    assert validate(output(documents=[row(source_id="source-1")]), req).documents == []


@pytest.mark.parametrize("archive", [False, True])
def test_heading_does_not_cross_attachment_or_zip_member_boundaries(archive):
    attachments = [
        {"fileName": "공고문.pdf", "extractedText": "제출서류\n정관 사본"},
        {"fileName": "별도 안내문.pdf", "extractedText": "1. 신청서 1부"},
    ]
    if archive:
        attachments = [{
            "fileName": "자료.zip",
            "extractedText": "[파일: 공고문.pdf]\n제출서류\n정관 사본\n"
            "[파일: 별도 안내문.pdf]\n1. 신청서 1부",
        }]
    for item in attachments:
        item["downloadUrl"] = "https://example.go.kr/file"
    req = with_document(attachments=attachments)
    assert validate(output(documents=[row(source_id="source-2")]), req).documents == []


def test_heading_does_not_make_a_fabricated_quote_valid():
    req = with_document(contentText="□ 제출서류\n1. 신청서 1부")
    assert validate(output(documents=[row(quote="신청서 2부")]), req).documents == []


def test_following_rows_condition_is_not_inherited_with_the_heading():
    source = "◦ 제출서류\n구분 서류명\n필수 제출\n1. 신청서 1부\n필요시 제출\n추가 증빙자료"
    req = with_document(contentText=source)
    brief = validate(output(documents=[row(condition="필요시 제출")]), req)
    assert [item.title for item in brief.documents] == ["신청서"]
    assert brief.documents[0].condition is None
    assert brief.note == "제출 대상·조건 확인 필요"
    body = TemplateReportGenerator().generate(req, briefs={2: brief}).summary
    assert "• 신청서\n" in body
    assert "필요시 제출" not in body


def test_section_recovery_revalidates_cached_output_without_another_model_call():
    enrichment._CACHE.clear()
    try:
        req = with_document(contentText="◦ 제출서류\n1. 신청서 1부")
        runner = SimpleNamespace(extract=AsyncMock(return_value=output(documents=[row()])))
        run(req, runner)
        assert [item.title for item in run(req, runner)[2].documents] == ["신청서"]
        runner.extract.assert_awaited_once()
    finally:
        enrichment._CACHE.clear()



def test_flattened_source_stops_at_the_next_outline_heading():
    source = "◦ 제출서류 구분 서류명 정관 사본 6. 문의처 담당 부서 1. 신청서 1부"
    assert validate(output(documents=[row()]), with_document(contentText=source)).documents == []


def test_quote_cannot_span_two_sections_even_if_it_occurs_in_the_source():
    quote = "1. 신청서 1부\n6. 문의처"
    req = with_document(contentText="제출서류\n" + quote)
    assert validate(output(documents=[row(quote=quote)]), req).documents == []
