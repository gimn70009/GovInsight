import json

from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.tools import (
    AnalysisToolContext,
    compare_with_previous_version,
    read_attachment_texts,
    read_document_content,
)


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
