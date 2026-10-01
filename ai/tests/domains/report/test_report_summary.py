"""Report summary selection regression from the saved Korea-Sweden notice."""

import pytest

from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_template import with_document

TITLE = "2026년도 한-스웨덴 공동연구사업 신규과제 선정결과 공고"
FIRST_SENTENCE = (
    "과학기술정보통신부의 공고는 2026년도 한-스웨덴 공동연구사업의 신규과제 "
    "선정결과를 알리는 결과 공고입니다."
)
WRONG_PURPOSE = "원-스웨덴 공동연구사업의 신규과제 선정결과를 공고합니다."


@pytest.mark.parametrize("change_type", ["NEW_DOCUMENT", "UNCHANGED_DOCUMENT"])
@pytest.mark.parametrize("document_count", [1, 100])
def test_report_uses_primary_summary_even_when_saved_purpose_has_a_wrong_name(
    change_type, document_count,
):
    request = with_document(
        title=TITLE,
        summary=FIRST_SENTENCE + " 선정된 연구과제의 협약 절차는 별도 안내합니다.",
        comparisonSummary={"purpose": WRONG_PURPOSE},
        changeType=change_type,
    )
    request = request.model_copy(update={"documents": request.documents * document_count})
    body = TemplateReportGenerator().generate(request).summary
    assert "요약: " + FIRST_SENTENCE in body
    assert "원-스웨덴" not in body
    assert "선정된 연구과제의 협약 절차는 별도 안내합니다." in body
    assert request.documents[0].comparison_summary.purpose == WRONG_PURPOSE
    assert len(body.encode("utf-16-le")) // 2 <= 20000
    if document_count > 1:
        assert "그 외" in body


def test_long_first_sentence_is_not_cut_or_replaced_with_a_different_purpose():
    request = with_document(
        summary="사업별 지원 조건과 참여 대상에 관한 설명을 " * 15 + "안내합니다. 후속 설명입니다.",
        comparisonSummary={"purpose": WRONG_PURPOSE},
    )
    body = TemplateReportGenerator().generate(request).summary
    assert "요약: " + request.documents[0].summary in body
    assert "원-스웨덴" not in body and "…" not in body and "후속 설명입니다." in body


def test_analysis_unavailable_notice_is_not_replaced_with_comparison_purpose():
    body = TemplateReportGenerator().generate(with_document(
        summary="분석 결과를 확인하지 못했습니다.",
        comparisonSummary={"purpose": WRONG_PURPOSE},
    )).summary
    assert "요약: 분석 결과를 확인하지 못했습니다." in body
    assert "원-스웨덴" not in body


def test_summary_keeps_urls_and_dates_inside_the_first_sentence():
    first = "신청은 https://example.org/v1.2/apply에서 2026. 10. 1.까지 접수합니다."
    body = TemplateReportGenerator().generate(with_document(
        summary=first + " 두 번째 문장입니다.",
    )).summary
    assert "요약: " + first in body
    assert "두 번째 문장입니다." in body


def test_full_summaries_are_kept_beyond_telegram_limit():
    summary = "지원 조건과 접수 절차를 안내합니다. " * 30
    request = with_document(summary=summary)
    docs = [request.documents[0].model_copy(update={"title": f"공고 {i}", "version_id": i + 1})
            for i in range(8)]
    body = TemplateReportGenerator().generate(
        request.model_copy(update={"documents": docs})
    ).summary
    assert len(body) > 4096
    assert body.count("요약: " + summary.strip()) == 8
    assert "그 외" not in body and "요약 전문은" not in body


def test_compact_storage_fallback_keeps_complete_summary_and_surrogate_pairs():
    summary = "😀 지원 대상과 조건을 안내합니다. " * 20
    req = with_document(summary=summary)
    req = req.model_copy(update={"documents": req.documents * 100})
    body = TemplateReportGenerator().generate(req).summary
    assert "요약: " + summary.strip() + "\n" in body
    assert "그 외" in body
    assert len(body.encode("utf-16-le")) // 2 <= 20000
