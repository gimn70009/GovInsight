from dataclasses import replace

import pytest

from app.domains.analysis.schemas.result import RequirementSource
from app.domains.report.brief import fallback_brief
from app.domains.report.submission_documents import SourcePart, SubmissionDocument
from app.domains.report.template import TemplateReportGenerator, _document_block
from tests.domains.report.test_report_checklist_reuse import brief, ready_request
from tests.domains.report.test_report_source_documents import APPLICATION_NAMES

URL = "https://example.go.kr/form?id=2&seq=1"


@pytest.mark.parametrize("path", ["template", "validated", "fallback"])
@pytest.mark.parametrize("alias", [
    "사업계획서(붙임2 양식)", "사업 계획서 (붙임 제2호 서식)",
    "사업계획서（붙임 2 양식）", "사업계획서(양식)",
])
def test_report_displays_one_plan_and_counts_remaining_documents_after_deduplication(path, alias):
    req = ready_request([*APPLICATION_NAMES, alias, "개인정보 동의서"])
    doc = req.documents[0]
    doc.title = "2026년 첨단분야 인턴십 지원사업 4차 공고"
    # Use the same collected form that both names refer to in this reproduction.
    attachment = type(doc.attachments[0])(
        file_name="붙임2 사업계획서.hwp", download_url=URL,
    )
    doc.attachments = [attachment]
    before = doc.proposal.model_dump()
    result = None
    if path == "validated":
        result = brief(req)
    elif path == "fallback":
        result = fallback_brief(doc, "제출 안내 생성 실패")
    body = TemplateReportGenerator().generate(
        req, briefs={doc.detection_id: result} if result else None,
    ).summary
    assert body.count("사업계획서") == 1
    assert f"[사업계획서]({URL})" in body
    assert "• 개인정보 동의서" in body
    assert "추가 제출서류" not in body
    assert doc.proposal.model_dump() == before
    compact = _document_block(doc, compact=True, brief=result)
    assert "추가 제출서류 3종:" in compact


def render(items):
    req = ready_request()
    result = replace(brief(req), documents=items)
    return TemplateReportGenerator().generate(
        req, briefs={req.documents[0].detection_id: result},
    ).summary


@pytest.mark.parametrize("reverse", [False, True])
def test_duplicate_preserves_the_available_download_and_original_first_label(reverse):
    items = [
        SubmissionDocument("사업계획서", None),
        SubmissionDocument("사업계획서(붙임2 양식)", SourcePart("붙임2 사업계획서.hwp", "", URL)),
    ]
    if reverse:
        items.reverse()
    body = render(items)
    assert body.count("사업계획서") == 1
    assert f"[{items[0].title}]({URL})" in body


@pytest.mark.parametrize("titles", [
    ["사업계획서(개인)", "사업계획서(단체)"],
    ["국문 사업계획서", "영문 사업계획서"],
    ["2025년 사업계획서", "2026년 사업계획서"],
    ["사업계획서(주관기관)", "사업계획서(참여기관)"],
    ["사업계획서(붙임2 양식, 개인)", "사업계획서(붙임2 양식, 단체)"],
    ["사업계획서(붙임2 양식)", "사업계획서(붙임3 양식)"],
])
def test_identity_qualifiers_and_conflicting_form_numbers_are_preserved(titles):
    form = SourcePart("제출양식.hwp", "", URL)
    body = render([SubmissionDocument(title, form) for title in titles])
    for title in titles:
        assert title in body
    assert body.count(f"]({URL})") == 1


@pytest.mark.parametrize("archive", [None, "제출양식.zip"])
def test_shared_download_keeps_member_names_with_one_download(archive):
    titles = ["사업계획서", "사업계획서(붙임2 양식)"]
    items = [SubmissionDocument(title, SourcePart(name, "", URL, archive))
             for title, name in zip(titles, ["A/사업계획서.hwp", "B/사업계획서.hwp"], strict=True)]
    body = render(items)
    assert body.count("사업계획서") == 2
    assert body.count(f"]({URL})") == 1
    assert "관련 서류: " in body
    assert ("[제출양식.zip]" if archive else "[통합 서류 파일]") in body


def test_identical_archive_member_keeps_one_zip_download():
    form = SourcePart("사업계획서.hwp", "", URL, "제출양식.zip")
    body = render([SubmissionDocument(title, form)
                   for title in ["사업계획서", "사업계획서(붙임2 양식)"]])
    assert body.count("사업계획서") == 1
    assert f"[사업계획서 (ZIP)]({URL})" in body


@pytest.mark.parametrize("reverse", [False, True])
def test_generic_name_does_not_choose_between_conflicting_forms(reverse):
    items = [
        SubmissionDocument("사업계획서", None),
        SubmissionDocument("사업계획서(붙임2 양식)", None),
        SubmissionDocument("사업계획서(붙임3 양식)", None),
    ]
    if reverse:
        items.reverse()
    body = render(items)
    assert body.count("사업계획서") == 3


def test_distinct_downloads_are_not_merged():
    body = render([
        SubmissionDocument("사업계획서", SourcePart("사업계획서.hwp", "", URL)),
        SubmissionDocument("사업계획서(붙임2 양식)",
                           SourcePart("사업계획서.hwp", "", "https://example.go.kr/other")),
    ])
    assert body.count("사업계획서") == 2


PLAN_QUOTE = (
    "사업계획서\nhwp\n‣붙임2 참고\n* 사업계획서 양식 내 별첨1 포함하여 제출\n"
    "* 파일명 : 인턴십 지원(4차)_분야_ㅇㅇ대학교"
)
ALIAS_QUOTE = "사업계획서 hwp ▶ 붙임2 참고 * 사업계획서 양식 내 [별첨1] 포함하여 제출"


def plan_source(quote=PLAN_QUOTE, name="[붙임2] 사업 신청 안내 및 사업계획서 양식.hwp"):
    return RequirementSource(
        origin="ATTACHMENT", attachment_name=name, section_title="신청서류", excerpt=quote,
    )


@pytest.mark.parametrize("path", ["template", "validated", "fallback"])
@pytest.mark.parametrize("reverse", [False, True])
@pytest.mark.parametrize("alias", [
    "사업계획서(대학 제출용, 붙임2 양식)", "사업계획서(붙임2 양식; 대학 제출용)",
    "사업계획서（대학 제출용，붙임 2 양식）", "사업계획서(대학 제출용)(붙임2 양식)",
])
def test_college_submission_alias_keeps_only_linked_plan_from_same_requirement(
    path, reverse, alias,
):
    req = ready_request([*APPLICATION_NAMES, alias])
    doc = req.documents[0]
    doc.attachments = [type(doc.attachments[0])(
        file_name="[붙임2] 사업 신청 안내 및 사업계획서 양식.hwp", download_url=URL,
    )]
    items = doc.proposal.preparation.submission_documents
    items[0].source = plan_source()
    items[-1].source = plan_source(ALIAS_QUOTE)
    items[-1].applies_to = "대학 제출서류(대학이 업로드)"
    if reverse:
        items.reverse()
    before = doc.proposal.model_dump()
    result = brief(req) if path == "validated" else (
        fallback_brief(doc, "제출 안내 생성 실패") if path == "fallback" else None
    )
    body = TemplateReportGenerator().generate(
        req, briefs={doc.detection_id: result} if result else None,
    ).summary
    assert body.count("사업계획서") == 1
    assert f"[사업계획서]({URL})" in body
    assert "대학 제출용" not in body
    assert "추가 제출서류" not in body
    assert doc.proposal.model_dump() == before
    compact = _document_block(doc, compact=True, brief=result)
    assert "추가 제출서류 2종:" in compact


@pytest.mark.parametrize("titles", [
    ["사업계획서(대학 제출용, 붙임2 양식)", "사업계획서(기업 제출용, 붙임2 양식)"],
    ["사업계획서(대학 제출용, 붙임2 양식)", "사업계획서(대학 제출용, 붙임3 양식)"],
    ["사업계획서(대학 제출용, 국문, 붙임2 양식)", "사업계획서(대학 제출용, 영문, 붙임2 양식)"],
    ["사업계획서", "사업계획서(대학 제출용, 붙임2 양식)"],
])
def test_distinct_or_unproven_compound_qualifiers_are_preserved(titles):
    body = render([SubmissionDocument(title, None) for title in titles])
    for title in titles:
        assert f"• {title}" in body


@pytest.mark.parametrize("case", ["other_file", "unrelated_row", "missing_source"])
def test_generic_and_submitter_qualified_plan_require_same_source_or_form(case):
    source = plan_source(ALIAS_QUOTE)
    if case == "other_file":
        source = plan_source(ALIAS_QUOTE, "다른 서류 안내.hwp")
    elif case == "unrelated_row":
        source = plan_source("기업이 대학에 제출하는 별도 사업계획서와 추가 증빙자료입니다.")
    else:
        source = None
    items = [
        SubmissionDocument("사업계획서", SourcePart("양식.hwp", "", URL), source=plan_source()),
        SubmissionDocument("사업계획서(대학 제출용, 붙임2 양식)", None, source=source),
    ]
    assert render(items).count("사업계획서") == 2


def test_known_distinct_submitters_are_kept_even_with_shared_source_and_download():
    form = SourcePart("제출양식.hwp", "", URL)
    source = plan_source()
    titles = ["사업계획서", "사업계획서(대학 제출용, 붙임2 양식)",
              "사업계획서(기업 제출용, 붙임2 양식)"]
    body = render([SubmissionDocument(title, form, source=source) for title in titles])
    assert body.count("사업계획서") == 3
