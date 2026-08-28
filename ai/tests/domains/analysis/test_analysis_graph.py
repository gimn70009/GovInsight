import asyncio
from collections.abc import Sequence

import pytest

from app.domains.analysis.agent import SYSTEM_PROMPT, AgentAnalysis, _strategy_instruction
from app.domains.analysis.graph import (
    AnalysisWorkflowError,
    DocumentAnalysisWorkflow,
    _urgency_score,
)
from app.domains.analysis.schemas.request import (
    AnalysisChangeType,
    AnalysisDocumentRequest,
)
from app.domains.analysis.schemas.result import (
    AnalysisDraft,
    DocumentImportance,
    Eligibility,
    Favorability,
    OpportunityAssessment,
    OpportunityDimension,
    OpportunityDimensionType,
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


def opportunity(
    company_fit: int = 80,
    urgency: int = 90,
    urgency_reason: str = (
        "신청 마감까지 남은 5일이므로 즉시 참여 여부를 결정하고 제출을 준비할 필요가 있습니다."
    ),
) -> OpportunityAssessment:
    return OpportunityAssessment(
        dimensions=[
            OpportunityDimension(type=dimension_type, score=score, reason=reason)
            for dimension_type, score, reason in (
                (
                    OpportunityDimensionType.COMPANY_FIT,
                    company_fit,
                    (
                        "회사의 산업 AI 역량과 수행 경험을 활용할 수 있지만 "
                        "공고상 참여 자격은 추가 확인이 필요합니다."
                    ),
                ),
                (
                    OpportunityDimensionType.BUSINESS_VALUE,
                    70,
                    "회사가 신규 공공 레퍼런스를 확보하고 유사 사업으로 확장할 가능성이 있습니다.",
                ),
                (
                    OpportunityDimensionType.FEASIBILITY,
                    60,
                    (
                        "회사의 기술 역량은 활용할 수 있지만 지원 자격과 투입 인력을 "
                        "추가로 확인해야 합니다."
                    ),
                ),
                (
                    OpportunityDimensionType.URGENCY,
                    urgency,
                    urgency_reason,
                ),
                (
                    OpportunityDimensionType.EVIDENCE_CONFIDENCE,
                    65,
                    (
                        "공식 원문과 회사 수행 사례는 확인되지만 "
                        "기업 규모 증빙은 추가 확인이 필요합니다."
                    ),
                ),
            )
        ]
    )


def analysis(
    favorability: Favorability,
    used_tools: list[str] | None = None,
    proposal_titles: list[str] | None = None,
    proposal_body: str = "산업 AI 적용 가능성과 세부 자격 조건을 검토해 사업화를 추진합니다.",
    opportunity_assessment: OpportunityAssessment | None = None,
    importance: DocumentImportance = DocumentImportance.HIGH,
) -> AgentAnalysis:
    titles = proposal_titles or ["핵심 판단", "활용·추진 방안", "필요 파트너·준비사항", "즉시 실행"]
    return AgentAnalysis(
        draft=AnalysisDraft(
            summary="중소기업을 대상으로 신청 기한이 정해진 지원사업 공고입니다.",
            key_points=["신청 기한은 9월 30일입니다."],
            importance=importance,
            reason="기업의 신청 기한이 명시되어 빠른 검토가 필요합니다.",
            eligibility=Eligibility.REVIEW_REQUIRED,
            favorable_or_not=favorability,
            proposal=ProposalStrategy(
                sections=[ProposalSection(title=title, body=proposal_body) for title in titles]
            ),
            opportunity=opportunity_assessment or opportunity(),
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
    assert len(result.opportunity.dimensions) == 5


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
    assert len(result.opportunity.dimensions) == 5
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
    runner = SequencedRunner(
        [
            analysis(Favorability.NOT_APPLICABLE, proposal_titles=["1. 접점", "7. 최종"]),
            analysis(Favorability.NOT_APPLICABLE),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert [section.title for section in result.proposal.sections] == [
        "핵심 판단",
        "활용·추진 방안",
        "필요 파트너·준비사항",
        "즉시 실행",
    ]
    assert "제안 섹션 제목과 순서" in (runner.feedbacks[1] or "")


def test_low_domain_fit_requires_conservative_proposal_sections() -> None:
    conservative_titles = [
        "핵심 판단",
        "도메인 불일치 근거",
        "재검토 조건",
        "현재 대응",
    ]
    low_fit = opportunity(company_fit=35)
    runner = SequencedRunner(
        [
            analysis(
                Favorability.NOT_APPLICABLE,
                opportunity_assessment=low_fit,
                importance=DocumentImportance.LOW,
            ),
            analysis(
                Favorability.NOT_APPLICABLE,
                proposal_titles=conservative_titles,
                opportunity_assessment=low_fit,
                importance=DocumentImportance.LOW,
            ),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert [section.title for section in result.proposal.sections] == conservative_titles
    assert "회사 적합도에 맞는 제안 섹션" in (runner.feedbacks[1] or "")


def test_unchanged_document_requires_neutral_impact() -> None:
    runner = SequencedRunner(
        [
            analysis(
                Favorability.NEUTRAL,
                proposal_titles=["현재 상태", "유지할 대응", "다음 확인"],
            )
        ]
    )
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


def test_system_prompt_uses_consistent_polite_tone() -> None:
    assert "모두 정중한 `합니다체`" in SYSTEM_PROMPT
    assert "`~한다`, `~이다`, `~있다`" in SYSTEM_PROMPT
    assert "`~을 권장합니다`" in SYSTEM_PROMPT


def test_graph_retries_high_importance_without_company_relevance() -> None:
    runner = SequencedRunner(
        [
            analysis(
                Favorability.NOT_APPLICABLE,
                opportunity_assessment=opportunity(company_fit=35),
            ),
            analysis(Favorability.NOT_APPLICABLE),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert result.importance == DocumentImportance.HIGH
    assert "HIGH 중요도는 회사 관련성이 확인" in (runner.feedbacks[1] or "")


def test_system_prompt_calibrates_importance_and_opportunity_scores() -> None:
    assert "마감일이 임박했다는 사실만으로 HIGH" in SYSTEM_PROMPT
    assert "핵심 산업: 반도체·디스플레이·철강과 직접 일치 40점" in SYSTEM_PROMPT
    assert "단순 모니터링·관제·데이터 수집·시각화·플랫폼 통합·시스템 운영 0점" in SYSTEM_PROMPT
    assert "직접 경제가치: 계약·지원 금액과 회사 수혜가 명시됨 35점" in SYSTEM_PROMPT
    assert "신청·입찰 자격: 회사가 직접 충족함이 확인됨 30점" in SYSTEM_PROMPT
    assert "3~5일: 90점" in SYSTEM_PROMPT
    assert "공고 원문 완결성" in SYSTEM_PROMPT
    assert "AI 과업이 명시되지 않으면 COMPANY_FIT은 40점을 넘기지 않습니다" in SYSTEM_PROMPT
    assert "핵심 산업의 직접 일치와 제조 AI 에이전트 과업" in SYSTEM_PROMPT
    assert "단순히 해당 기관이나 기업이 자산·설비를 보유한다는 이유" in SYSTEM_PROMPT
    assert (
        "COMPANY_FIT이 40점 이하이면 현재 문서를 사업화 기회로 확장하지 않습니다"
        in SYSTEM_PROMPT
    )
    assert "URGENCY는 대응까지 남은 시간만 평가" in SYSTEM_PROMPT


@pytest.mark.parametrize(
    ("remaining_days", "expected_score"),
    [
        (0, 100),
        (2, 100),
        (3, 90),
        (5, 90),
        (6, 80),
        (7, 80),
        (8, 65),
        (14, 65),
        (15, 50),
        (21, 50),
        (22, 35),
        (30, 35),
        (31, 20),
        (45, 20),
        (46, 10),
    ],
)
def test_urgency_score_uses_remaining_day_rubric(
    remaining_days: int,
    expected_score: int,
) -> None:
    assert _urgency_score(remaining_days) == expected_score


def test_graph_normalizes_urgency_score_when_it_does_not_match_remaining_days() -> None:
    runner = SequencedRunner(
        [
            analysis(
                Favorability.NOT_APPLICABLE,
                opportunity_assessment=opportunity(
                    urgency=40,
                    urgency_reason=(
                        "신청 마감까지 남은 5일이므로 "
                        "즉시 참여 여부를 결정할 필요가 있습니다."
                    ),
                ),
            )
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    urgency = result.opportunity.dimensions[3]
    assert runner.call_count == 1
    assert urgency.score == 90
    assert "남은 5일" in urgency.reason


def test_graph_normalizes_internal_company_profile_field_without_retry() -> None:
    runner = SequencedRunner(
        [
            analysis(
                Favorability.NOT_APPLICABLE,
                opportunity_assessment=opportunity(
                    urgency_reason=(
                        "마감까지 남은 5일이며 회사의 targetIndustries와 일치하지만 "
                        "unknownFields는 확인이 필요합니다."
                    )
                ),
            ),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert runner.call_count == 1
    reason = result.opportunity.dimensions[3].reason
    assert "targetIndustries" not in reason
    assert "unknownFields" not in reason
    assert "회사의 대상 산업" in reason
    assert "추가 확인이 필요한 회사 정보" in reason
    assert result.opportunity.dimensions[3].score == 90


def test_graph_stops_after_max_attempts() -> None:
    runner = SequencedRunner([RuntimeError("모델 호출 실패"), RuntimeError("모델 호출 실패")])
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    with pytest.raises(AnalysisWorkflowError, match="모델 호출 실패"):
        asyncio.run(workflow.analyze(document()))
