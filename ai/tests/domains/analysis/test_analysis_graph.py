import asyncio
from collections.abc import Sequence

import pytest

from app.domains.analysis.agent import AgentAnalysis, _strategy_instruction
from app.domains.analysis.graph import AnalysisWorkflowError, DocumentAnalysisWorkflow
from app.domains.analysis.schemas.request import (
    AnalysisChangeType,
    AnalysisDocumentRequest,
)
from app.domains.analysis.schemas.result import (
    AnalysisDraft,
    DocumentImportance,
    Eligibility,
    Favorability,
    ProposalSection,
    ProposalStrategy,
)


def document(change_type: str = "NEW_DOCUMENT") -> AnalysisDocumentRequest:
    payload: dict[str, object] = {
        "detectionId": 10,
        "documentId": 20,
        "versionId": 30,
        "changeType": change_type,
        "organizationName": "산업통상부",
        "boardName": "사업공고",
        "title": "중소기업 지원사업 공고",
        "contentText": "중소기업은 9월 30일까지 신청할 수 있습니다.",
        "originalUrl": "https://example.go.kr/article/30",
    }
    if change_type == "UPDATED_DOCUMENT":
        payload["previousVersion"] = {
            "versionId": 29,
            "title": "중소기업 지원사업 공고",
            "contentText": "중소기업은 8월 31일까지 신청할 수 있습니다.",
        }
        payload["previousAnalysis"] = {
            "summary": "기존 공고 분석",
            "proposalDirection": "기존에는 8월 제출을 준비합니다.",
        }
    return AnalysisDocumentRequest.model_validate(payload)


def analysis(
    favorability: Favorability,
    used_tools: list[str] | None = None,
    proposal_titles: list[str] | None = None,
    proposal_body: str = "산업 AI 적용 가능성과 세부 자격 조건을 검토해 사업화를 추진합니다.",
) -> AgentAnalysis:
    titles = proposal_titles or ["핵심 판단", "활용·추진 방안", "필요 파트너·준비사항", "즉시 실행"]
    return AgentAnalysis(
        draft=AnalysisDraft(
            summary="중소기업을 대상으로 신청 기한이 정해진 지원사업 공고입니다.",
            key_points=["신청 기한은 9월 30일입니다."],
            importance=DocumentImportance.HIGH,
            reason="기업의 신청 기한이 명시되어 빠른 검토가 필요합니다.",
            eligibility=Eligibility.REVIEW_REQUIRED,
            favorable_or_not=favorability,
            proposal=ProposalStrategy(
                sections=[ProposalSection(title=title, body=proposal_body) for title in titles]
            ),
        ),
        used_tools=used_tools or ["get_document_content", "get_company_profile"],
        model_name="mock-model",
    )


class SequencedRunner:
    def __init__(self, outcomes: Sequence[AgentAnalysis | Exception]) -> None:
        self._outcomes = iter(outcomes)
        self.call_count = 0
        self.feedbacks: list[str | None] = []
        self.change_types: list[str] = []

    async def analyze(
        self,
        analysis_document: AnalysisDocumentRequest,
        feedback: str | None = None,
    ) -> AgentAnalysis:
        self.call_count += 1
        self.feedbacks.append(feedback)
        self.change_types.append(analysis_document.change_type)
        outcome = next(self._outcomes)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def test_graph_retries_transient_failure_and_returns_new_document_result() -> None:
    runner = SequencedRunner(
        [
            TimeoutError("첫 호출 시간 초과"),
            analysis(Favorability.NOT_APPLICABLE),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert runner.call_count == 2
    assert runner.feedbacks[1] == "이전 분석 시도가 실패했습니다: 첫 호출 시간 초과"
    assert result.detection_id == 10
    assert result.importance == DocumentImportance.HIGH
    assert result.favorable_or_not == Favorability.NOT_APPLICABLE


def test_graph_returns_business_rule_feedback_before_retrying_new_document() -> None:
    runner = SequencedRunner(
        [
            analysis(Favorability.FAVORABLE),
            analysis(Favorability.NOT_APPLICABLE),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert result.favorable_or_not == Favorability.NOT_APPLICABLE
    assert "신규 문서의 favorable_or_not" in (runner.feedbacks[1] or "")


def test_updated_document_requires_comparison_tools_and_revises_plan() -> None:
    required_tools = [
        "get_document_content",
        "get_company_profile",
        "compare_previous_version",
        "get_previous_analysis",
    ]
    runner = SequencedRunner(
        [
            analysis(Favorability.REVIEW_REQUIRED),
            analysis(
                Favorability.FAVORABLE,
                used_tools=required_tools,
                proposal_titles=["변경 요약", "회사 영향", "대응 조정", "즉시 실행"],
                proposal_body=(
                    "연장된 기한에 맞춰 기존 제안 일정을 조정하고 "
                    "파트너와 제출 자료를 재점검합니다."
                ),
            ),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document("UPDATED_DOCUMENT")))

    assert runner.change_types == ["UPDATED_DOCUMENT", "UPDATED_DOCUMENT"]
    assert "compare_previous_version" in (runner.feedbacks[1] or "")
    assert result.favorable_or_not == Favorability.FAVORABLE
    assert "기존 제안 일정" in result.proposal.sections[0].body


def test_graph_retries_when_proposal_sections_do_not_match_change_type() -> None:
    runner = SequencedRunner([
        analysis(Favorability.NOT_APPLICABLE, proposal_titles=["1. 접점", "7. 최종"]),
        analysis(Favorability.NOT_APPLICABLE),
    ])
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert [section.title for section in result.proposal.sections] == [
        "핵심 판단",
        "활용·추진 방안",
        "필요 파트너·준비사항",
        "즉시 실행",
    ]
    assert "제안 섹션 제목과 순서" in (runner.feedbacks[1] or "")


def test_unchanged_document_requires_neutral_impact() -> None:
    runner = SequencedRunner([analysis(
        Favorability.NEUTRAL,
        proposal_titles=["현재 상태", "유지할 대응", "다음 확인"],
    )])
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document("UNCHANGED_DOCUMENT")))

    assert runner.change_types == ["UNCHANGED_DOCUMENT"]
    assert result.favorable_or_not == Favorability.NEUTRAL


@pytest.mark.parametrize(
    ("change_type", "section_titles"),
    [
        (
            AnalysisChangeType.NEW_DOCUMENT,
            ("핵심 판단", "활용·추진 방안", "필요 파트너·준비사항", "즉시 실행"),
        ),
        (
            AnalysisChangeType.UPDATED_DOCUMENT,
            ("변경 요약", "회사 영향", "대응 조정", "즉시 실행"),
        ),
        (
            AnalysisChangeType.UNCHANGED_DOCUMENT,
            ("현재 상태", "유지할 대응", "다음 확인"),
        ),
    ],
)
def test_change_type_strategy_defines_readable_proposal_sections(
    change_type: AnalysisChangeType,
    section_titles: tuple[str, ...],
) -> None:
    instruction = _strategy_instruction(change_type)

    assert all(title in instruction for title in section_titles)

def test_graph_stops_after_max_attempts() -> None:
    runner = SequencedRunner([RuntimeError("모델 호출 실패"), RuntimeError("모델 호출 실패")])
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    with pytest.raises(AnalysisWorkflowError, match="모델 호출 실패"):
        asyncio.run(workflow.analyze(document()))
