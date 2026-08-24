import asyncio

import pytest

from app.domains.analysis.agent import AgentAnalysis
from app.domains.analysis.graph import AnalysisWorkflowError, DocumentAnalysisWorkflow
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.schemas.result import (
    AnalysisDraft,
    DocumentImportance,
    Eligibility,
    Favorability,
)


def document() -> AnalysisDocumentRequest:
    return AnalysisDocumentRequest.model_validate(
        {
            "detectionId": 10,
            "documentId": 20,
            "versionId": 30,
            "changeType": "NEW_DOCUMENT",
            "organizationName": "산업통상부",
            "boardName": "사업공고",
            "title": "중소기업 지원사업 공고",
            "contentText": "중소기업은 9월 30일까지 신청할 수 있습니다.",
            "originalUrl": "https://example.go.kr/article/30",
        }
    )


class RetryRunner:
    def __init__(self) -> None:
        self.call_count = 0

    async def analyze(self, _document: AnalysisDocumentRequest) -> AgentAnalysis:
        self.call_count += 1
        if self.call_count == 1:
            raise TimeoutError("첫 호출 시간 초과")
        return AgentAnalysis(
            draft=AnalysisDraft(
                summary="중소기업을 대상으로 신청 기한이 정해진 지원사업 공고입니다.",
                key_points=["신청 기한은 9월 30일입니다."],
                importance=DocumentImportance.HIGH,
                reason="기업의 신청 기한이 명시되어 빠른 검토가 필요합니다.",
                eligibility=Eligibility.REVIEW_REQUIRED,
                favorable_or_not=Favorability.NOT_APPLICABLE,
                proposal_direction="산업 AI 적용 가능성과 세부 자격 조건을 추가로 검토합니다.",
            ),
            used_tools=["get_document_content"],
            model_name="mock-model",
        )


class FailingRunner:
    async def analyze(self, _document: AnalysisDocumentRequest) -> AgentAnalysis:
        raise RuntimeError("모델 호출 실패")


def test_graph_retries_and_returns_structured_result() -> None:
    runner = RetryRunner()
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert runner.call_count == 2
    assert result.detection_id == 10
    assert result.importance == DocumentImportance.HIGH
    assert result.used_tools == ["get_document_content"]


def test_graph_stops_after_max_attempts() -> None:
    workflow = DocumentAnalysisWorkflow(runner=FailingRunner(), max_attempts=2)

    with pytest.raises(AnalysisWorkflowError, match="모델 호출 실패"):
        asyncio.run(workflow.analyze(document()))
