import pytest

from app.domains.analysis.proposals.drafting import _verified_items
from app.domains.analysis.proposals.submission_requirements import collect_submission_requirements
from app.domains.analysis.schemas.result import PreparationChecklistItem, RequirementSource
from tests.domains.analysis.proposals.test_submission_requirements import request


@pytest.mark.parametrize("note,expected", [("선택", "OPTIONAL"), ("해당없음", None)])
def test_short_table_status_is_not_assumed_mandatory(note, expected):
    req = request(f"신청서류\n서류명 파일형식 비고\n사업계획서 hwp {note}")
    rows = collect_submission_requirements(req)
    assert [row.level for row in rows] == ([expected] if expected else [])


def test_unclear_later_stage_is_not_assumed_to_be_application():
    req = request("협약 준비 제출서류\n서류명 파일형식 비고\n사업계획서 hwp 필수")
    assert collect_submission_requirements(req) == []


def test_quote_from_another_zip_entry_does_not_validate_the_named_entry():
    item = PreparationChecklistItem(
        title="사업계획서",
        detail="사업계획서를 작성해 제출해야 합니다.",
        next_action="담당자가 서류를 작성합니다.",
        source=RequirementSource(
            origin="ATTACHMENT",
            attachment_name="현재사업.hwp",
            section_title="제출 안내",
            excerpt="사업계획서를 필수로 제출합니다.",
        ),
    )
    text = (
        "[파일: 다른사업.hwp]\n사업계획서를 필수로 제출합니다.\n"
        "[파일: 현재사업.hwp]\n이 사업은 계획서 제출을 요구하지 않습니다."
    )
    assert _verified_items([item], "본문", {"양식.zip": text}) == []
    correct = item.model_copy(deep=True)
    correct.source.attachment_name = "다른사업.hwp"
    assert _verified_items([correct], "본문", {"양식.zip": text}) == [correct]


def test_financial_row_with_filename_and_compressed_upload_condition_is_required():
    req = request(
        "라. 신청서류(온라인 제출)\n순번\n서류명\n파일형식\n비고\n"
        "1\n사업계획서\nhwp\n* 사업계획서 양식 내 별첨1 포함하여 제출\n"
        "* 파일명 : 인턴십 지원(4차)_분야_ㅇㅇ대학교\n"
        "2\n결산재무제표\npdf\n"
        "* 최근 3개년말 결산 재무제표(’23~‘25) 원본 또는 사본(원본 대조필)\n"
        "* 파일명 : 분야_ㅇㅇ대학교_재무제표.pdf/zip\n"
        "☞ 기업이 2개 이상인 경우, 압축 파일로 제출\n마. 유의사항"
    )
    rows = collect_submission_requirements(req)
    financial = next(row for row in rows if row.title == "결산재무제표")
    assert financial.level == "MANDATORY"
    assert "’23~‘25" in financial.source.excerpt
    assert "압축 파일로 제출" in financial.source.excerpt
