import pytest

from app.domains.report.brief import fallback_brief
from app.domains.report.submission_documents import SourcePart, SubmissionDocument
from app.domains.report.template import TemplateReportGenerator, _submission_entries
from tests.domains.report.test_report_checklist_reuse import brief, ready_request

URL = "https://example.org/download?id=1&seq=2"


@pytest.mark.parametrize("path", ["template", "validated", "fallback"])
def test_saved_checklist_aliases_are_grouped_in_every_report_path(path):
    titles = ["사업계획서", "결산재무제표", "표준 현장실습 학기제 운영계획서",
              "사업자등록증", "기관소개 자료"]
    request = ready_request(titles)
    document = request.documents[0]
    document.attachments = [type(document.attachments[0])(
        file_name="붙임2 사업계획서 및 표준 현장실습 학기제 운영계획서.hwp",
        download_url=URL,
    )]
    before = document.proposal.model_dump()
    result = brief(request) if path == "validated" else (
        fallback_brief(document, "모델 실패") if path == "fallback" else None
    )
    body = TemplateReportGenerator().generate(
        request, briefs={document.detection_id: result} if result else None,
    ).summary
    assert body.count(f"]({URL})") == 1
    assert "관련 서류: 사업계획서 · 표준 현장실습 학기제 운영계획서" in body
    assert all(title in body for title in titles)
    assert "추가 제출서류" not in body
    assert document.proposal.model_dump() == before


def test_different_names_for_one_file_have_one_download_and_keep_requirements():
    form = SourcePart("붙임2 사업계획서.hwp", "", URL)
    documents = [
        SubmissionDocument("사업계획서", form),
        SubmissionDocument("결산재무제표", None),
        SubmissionDocument("표준 현장실습 학기제 운영계획서", form),
    ]
    assert _submission_entries(documents) == [
        f"• [붙임2 사업계획서.hwp]({URL})\n"
        "  ↳ 관련 서류: 사업계획서 · 표준 현장실습 학기제 운영계획서",
        "• 결산재무제표",
    ]
    assert len(documents) == 3


def test_archive_members_and_optional_requirement_share_one_download():
    documents = [
        SubmissionDocument("신청서", SourcePart("신청서.hwp", "", URL, "양식.zip")),
        SubmissionDocument("동의서", SourcePart("동의서.hwp", "", URL, "양식.zip"),
                           requirement_level="OPTIONAL"),
    ]
    assert _submission_entries(documents) == [
        f"• [양식.zip]({URL})\n  ↳ 관련 서류: 신청서 · 동의서 (선택 제출)",
    ]


def test_fragment_is_same_download_but_query_is_distinct():
    documents = [
        SubmissionDocument("사업계획서", SourcePart("양식.pdf", "", URL + "#page=1")),
        SubmissionDocument("운영계획서", SourcePart("양식.pdf", "", URL + "#page=8")),
        SubmissionDocument("신청서", SourcePart("양식.pdf", "", URL + "0")),
    ]
    entries = _submission_entries(documents)
    assert len(entries) == 2
    assert "관련 서류: 사업계획서 · 운영계획서" in entries[0]
    assert URL + "0" in entries[1]


def test_unknown_or_unsafe_urls_do_not_imply_identical_files():
    documents = [
        SubmissionDocument("사업계획서", SourcePart("양식.hwp", "", None)),
        SubmissionDocument("운영계획서", SourcePart("양식.hwp", "", "javascript:alert(1)")),
    ]
    assert _submission_entries(documents) == ["• 사업계획서", "• 운영계획서"]
