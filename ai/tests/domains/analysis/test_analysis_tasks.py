import asyncio

from app.domains.analysis.graph import AnalysisWorkflowError
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.tasks import _analyze_documents


class SlowFailingWorkflow:
    def __init__(self) -> None:
        self.active_count = 0
        self.max_active_count = 0

    async def analyze(self, document: AnalysisDocumentRequest) -> None:
        self.active_count += 1
        self.max_active_count = max(self.max_active_count, self.active_count)
        try:
            await asyncio.sleep(0.02)
            raise AnalysisWorkflowError(f"분석 실패 {document.detection_id}")
        finally:
            self.active_count -= 1


def analysis_document(identifier: int) -> AnalysisDocumentRequest:
    return AnalysisDocumentRequest.model_validate(
        {
            "detectionId": identifier,
            "documentId": identifier,
            "versionId": identifier,
            "changeType": "NEW_DOCUMENT",
            "organizationName": "산업통상부",
            "boardName": "사업공고",
            "title": f"지원사업 공고 {identifier}",
            "contentText": "분석할 게시글 본문입니다.",
            "originalUrl": f"https://example.go.kr/notices/{identifier}",
        }
    )


def test_analyze_documents_limits_concurrency_and_isolates_failures() -> None:
    workflow = SlowFailingWorkflow()
    documents = [analysis_document(identifier) for identifier in range(1, 5)]

    results, failures = asyncio.run(_analyze_documents(workflow, documents, concurrency=2))

    assert results == []
    assert workflow.max_active_count == 2
    assert [failure.detection_id for failure in failures] == [1, 2, 3, 4]
