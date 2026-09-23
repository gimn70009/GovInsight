import json

from app.domains.analysis.evidence.agent import required_evidence_tools
from app.domains.analysis.evidence.tools import (
    AnalysisToolContext,
    _cached_result,
    compare_with_previous_version,
    read_attachment_texts,
    read_document_content,
)
from app.domains.analysis.schemas.request import AnalysisChangeType, AnalysisDocumentRequest
from tests.domains.analysis.evidence.fakes import collected_inputs


def document() -> AnalysisDocumentRequest:
    return AnalysisDocumentRequest.model_validate(
        {
            "detectionId": 1,
            "documentId": 2,
            "versionId": 3,
            "changeType": "UPDATED_DOCUMENT",
            "organizationName": "과학기술정보통신부",
            "boardName": "사업공고",
            "title": "수정된 지원사업 공고",
            "contentText": "접수 기한이 9월 30일로 변경되었습니다.",
            "originalUrl": "https://example.go.kr/view.do?id=10",
            "attachments": [
                {
                    "attachmentId": 4,
                    "fileName": "공고문.hwpx",
                    "extractedText": "지원 대상은 중소기업입니다.",
                }
            ],
            "previousVersion": {
                "versionId": 2,
                "title": "지원사업 공고",
                "contentText": "접수 기한은 9월 20일입니다.",
            },
        }
    )


def test_agent_reads_required_sources_and_passes_original_evidence() -> None:
    context = AnalysisToolContext(document=document(), max_text_chars=10_000)

    sections, used_tools = collected_inputs(context)

    combined = "\n".join(sections)
    assert "<current_document>" in combined
    assert "<company_profile>" in combined
    assert "BISTelligence" in combined
    assert "SYNTHETIC_DEMO" not in combined
    assert "DEMO-NEED-GPU" not in combined
    assert "<attachments>" in combined
    assert "<previous_version_diff>" in combined
    assert used_tools == [
        "get_document_content",
        "get_company_profile",
        "get_attachment_texts",
        "compare_previous_version",
    ]


def test_updated_document_requires_previous_version_comparison() -> None:
    assert "compare_previous_version" in required_evidence_tools(document())
    for kind in (AnalysisChangeType.NEW_DOCUMENT, AnalysisChangeType.UNCHANGED_DOCUMENT):
        assert "compare_previous_version" not in required_evidence_tools(
            document().model_copy(update={"change_type": kind}))


def test_read_document_and_attachment_texts() -> None:
    context = AnalysisToolContext(document=document(), max_text_chars=10_000)

    content = json.loads(read_document_content(context))
    attachments = json.loads(read_attachment_texts(context))

    assert content["title"] == "수정된 지원사업 공고"
    assert "9월 30일" in content["contentText"]
    assert attachments[0]["fileName"] == "공고문.hwpx"
    assert "중소기업" in attachments[0]["extractedText"]


def test_compare_previous_version() -> None:
    context = AnalysisToolContext(document=document(), max_text_chars=10_000)

    comparison = json.loads(compare_with_previous_version(context))

    assert comparison["available"] is True
    assert comparison["titleChanged"] is True
    assert "9월 20일" in comparison["contentDiff"]
    assert "9월 30일" in comparison["contentDiff"]


def test_reuses_cached_tool_result_within_document_context() -> None:
    context = AnalysisToolContext(document=document(), max_text_chars=10_000)
    calls = 0

    def produce() -> str:
        nonlocal calls
        calls += 1
        return "cached evidence"

    first = _cached_result(context, "test_tool", produce)
    second = _cached_result(context, "test_tool", produce)

    assert first == "cached evidence"
    assert second == first
    assert calls == 1


def test_runtime_company_context_contains_only_actual_profile() -> None:
    context = AnalysisToolContext(
        document=document(), max_text_chars=10_000,
    )
    sections, _ = collected_inputs(context)
    combined = "\n".join(sections)
    assert "BISTelligence" in combined
    assert "SYNTHETIC_DEMO" not in combined
    assert "DEMO-NEED-GPU" not in combined
