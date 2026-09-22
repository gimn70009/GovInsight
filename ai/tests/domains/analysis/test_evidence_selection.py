import json

from app.domains.analysis.evidence_selection import allocate_budgets, select_evidence
from app.domains.analysis.schemas.request import AnalysisAttachmentRequest
from app.domains.analysis.tools import (
    AnalysisToolContext,
    read_attachment_texts,
    read_document_content,
)
from tests.domains.analysis.test_analysis_tools import document


def test_middle_deadline_and_exclusion_survive_noise_with_exact_source_ranges():
    noise = "사업 배경 설명입니다. " * 150
    critical = "신청자격: 중소기업\n단, 체납 기업은 제외합니다.\n접수마감: 2026-10-12 18:00"
    source = noise + "\n\n" + critical + "\n\n" + noise
    selected = select_evidence(source, 500)
    assert critical in selected.text
    assert len(selected.text) <= 500
    assert selected.truncated
    for start, end in selected.ranges:
        assert source[start:end] in selected.text
    assert selected.metadata()["originalChars"] == len(source)


def test_conflicting_dates_are_retained_not_resolved_by_selector():
    source = (
        "배경입니다. " * 200
        + "\n\n신청마감: 2026-09-30 16:00\n\n"
        + "일반 안내입니다. " * 200
        + "\n\n정정 접수마감: 2026-10-15 18:00"
    )
    selected = select_evidence(source, 700)
    assert "2026-09-30 16:00" in selected.text
    assert "2026-10-15 18:00" in selected.text


def test_complete_small_source_is_unchanged_and_tiny_budget_is_explicit():
    source = "신청자격: 중소기업\n단, 휴업 기업 제외"
    assert select_evidence(source, 1000).text == source
    for limit in (0, 1, 20):
        result = select_evidence(source * 100, limit)
        assert len(result.text) <= limit and result.truncated
        assert not result.ranges


def test_long_unsplittable_condition_is_omitted_not_cut_into_false_statement():
    text = "신청대상: " + "조건 " * 3000 + "인 기업만 신청할 수 없습니다."
    result = select_evidence(text, 500)
    assert "신청대상" not in result.text
    assert result.truncated


def test_water_filling_redistributes_short_files_without_exceeding_budget():
    assert allocate_budgets([10, 1000, 1000], 210) == [10, 100, 100]
    assert allocate_budgets([0, 100], 0) == [0, 0]
    assert sum(allocate_budgets([100] * 100, 17)) == 17


def test_later_attachment_is_not_starved_and_duplicates_are_identified():
    first = "배경 안내입니다. " * 2000
    last = "제출처: 사업관리시스템 https://example.go.kr/apply\n접수기한: 2026-11-06 16:00"
    doc = document().model_copy(
        update={
            "attachments": [
                AnalysisAttachmentRequest(
                    attachment_id=1, file_name="자료집.pdf", extracted_text=first
                ),
                AnalysisAttachmentRequest(
                    attachment_id=2, file_name="공고문.pdf", extracted_text=last
                ),
                AnalysisAttachmentRequest(
                    attachment_id=3, file_name="공고문사본.pdf", extracted_text=last
                ),
                AnalysisAttachmentRequest(attachment_id=4, file_name="실패.pdf"),
            ]
        }
    )
    payload = json.loads(read_attachment_texts(AnalysisToolContext(doc, 900)))
    assert last in payload[1]["extractedText"]
    assert sum(len(item["extractedText"]) for item in payload) <= 900
    assert payload[2]["duplicateOfAttachmentId"] == 2
    assert not payload[2]["extractedText"]
    assert payload[3]["unavailableReason"]
    assert payload[0]["coverage"]["truncated"]


def test_document_tool_exposes_selection_coverage():
    doc = document().model_copy(update={"content_text": "배경입니다. " * 2000})
    payload = json.loads(read_document_content(AnalysisToolContext(doc, 1000)))
    assert payload["coverage"]["truncated"]
    assert len(payload["contentText"]) <= 1000


def test_exception_across_paragraph_boundary_is_not_separated_from_target():
    source = (
        "배경입니다. " * 1000 + "\n\n지원대상: 중소기업\n\n"
        "다만, 체납 기업은 지원 대상에서 제외합니다.\n\n" + "안내입니다. " * 1000
    )
    selected = select_evidence(source, 300)
    assert "지원대상: 중소기업" in selected.text
    assert "다만, 체납 기업은 지원 대상에서 제외합니다." in selected.text
