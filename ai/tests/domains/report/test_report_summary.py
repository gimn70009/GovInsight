"""Report summary selection regression from the saved Korea-Sweden notice."""

import pytest

from app.domains.report.template import TemplateReportGenerator, display_units
from tests.domains.report.test_report_template import with_document

TITLE = "2026년도 한-스웨덴 공동연구사업 신규과제 선정결과 공고"
FIRST_SENTENCE = (
    "과학기술정보통신부의 공고는 2026년도 한-스웨덴 공동연구사업의 신규과제 "
    "선정결과를 알리는 결과 공고입니다."
)
WRONG_PURPOSE = "원-스웨덴 공동연구사업의 신규과제 선정결과를 공고합니다."


@pytest.mark.parametrize("change_type", ["NEW_DOCUMENT", "UNCHANGED_DOCUMENT"])
@pytest.mark.parametrize("document_count", [1, 50])
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
    assert "협약 절차" not in body
    assert request.documents[0].comparison_summary.purpose == WRONG_PURPOSE
    assert display_units(body) <= 3900
    if document_count > 1:
        assert "그 외" in body


def test_long_first_sentence_is_not_cut_or_replaced_with_a_different_purpose():
    request = with_document(
        summary="사업별 지원 조건과 참여 대상에 관한 설명을 " * 15 + "안내합니다. 후속 설명입니다.",
        comparisonSummary={"purpose": WRONG_PURPOSE},
    )
    body = TemplateReportGenerator().generate(request).summary
    assert "요약: 요약 전문은 게시글 상세에서 확인해 주세요." in body
    assert "원-스웨덴" not in body and "…" not in body and "후속 설명" not in body


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
    assert "두 번째 문장" not in body
