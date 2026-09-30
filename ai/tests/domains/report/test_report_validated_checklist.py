from app.domains.analysis.proposals.submission_validation import validate_submission_documents
from app.domains.report.brief import fallback_brief
from app.domains.report.submission_documents import reusable_submission_documents
from app.domains.report.template import TemplateReportGenerator
from tests.domains.analysis.proposals.test_submission_requirements import request
from tests.domains.analysis.proposals.test_submission_validation import candidate
from tests.domains.report.test_report_checklist_reuse import ready_request


def test_report_reuses_only_confirmed_application_files_and_not_review_candidates():
    source = request()
    quote = "인턴십 사전교육 프로그램의 체계성 및 효과성 15"
    source.attachments[0].extracted_text += "\n" + quote
    documents, notes = validate_submission_documents([
        candidate(source, "별도 사전교육 계획서", quote),
        candidate(source, "기관소개 자료(기업 프로필)",
                  "기관소개 자료 ▸최초 참여 시 또는 홍보 목적 등 필요성이 있을 경우 제출"),
    ], source)
    report = ready_request()
    doc = report.documents[0]
    doc.proposal.preparation.submission_documents = documents
    doc.proposal.preparation.meeting_agenda.extend(notes)
    expected = [item.title for item in documents if item.stage == "APPLICATION"]
    reused = reusable_submission_documents(doc)
    assert reused is not None
    assert [item.title for item in reused] == expected
    assert len(expected) == 5
    assert len([item for item in reused if "기관소개" in item.title]) == 1
    brief = fallback_brief(doc, "제출 안내 생성 실패")
    assert [item.title for item in brief.documents] == expected
    body = TemplateReportGenerator().generate(report, briefs={doc.detection_id: brief}).summary
    assert "별도 사전교육 계획서" not in body
    assert "협약서" not in body
    assert "평가표 및 출석부" not in body
    assert "• 결산재무제표\n" in body
