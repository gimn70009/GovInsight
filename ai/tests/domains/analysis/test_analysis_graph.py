import asyncio
import logging
from collections.abc import Sequence
from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.analysis.agent import (
    SYSTEM_PROMPT,
    AgentAnalysis,
    LangChainAnalysisRunner,
    _normalize_base_proposal,
    _strategy_instruction,
)
from app.domains.analysis.graph import (
    AnalysisWorkflowError,
    DocumentAnalysisWorkflow,
    _normalize_proposal_document_type,
    _normalize_proposal_recommendation,
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
    ProposalDocumentType,
    ProposalDraftStatus,
    ProposalSection,
    ProposalStrategy,
)
from app.domains.analysis.tasks import _analyze_documents
from tests.domains.analysis.evidence_fakes import collect_all_evidence


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


def document_with_application_template() -> AnalysisDocumentRequest:
    payload = document().model_dump(by_alias=True)
    payload["attachments"] = [
        {
            "attachmentId": 1,
            "fileName": "연구개발계획서 및 제출서식.zip",
            "extractedText": "연구개발계획서를 작성해 제출해야 합니다.",
        }
    ]
    return AnalysisDocumentRequest.model_validate(payload)


def test_normalizes_non_proposal_draft_status_before_strict_validation() -> None:
    payload = {
        "eligibility": Eligibility.REVIEW_REQUIRED,
        "proposal": {
            "document_type": ProposalDocumentType.GENERAL_NOTICE,
            "draft_status": ProposalDraftStatus.REVIEW_REQUIRED,
        },
        "opportunity": {"dimensions": []},
    }

    _normalize_base_proposal(payload)

    assert payload["proposal"]["draft_status"] == ProposalDraftStatus.NOT_APPLICABLE


def test_normalizes_below_draft_threshold_proposal_to_not_recommended() -> None:
    payload = {
        "eligibility": Eligibility.REVIEW_REQUIRED,
        "proposal": {
            "document_type": ProposalDocumentType.PROPOSAL_REQUEST,
            "draft_status": ProposalDraftStatus.READY,
        },
        "opportunity": {
            "dimensions": [{"type": "COMPANY_FIT", "score": 60}],
        },
    }

    _normalize_base_proposal(payload)

    assert payload["proposal"]["draft_status"] == ProposalDraftStatus.NOT_RECOMMENDED


def test_normalizes_string_ineligible_and_clears_proposal_payload() -> None:
    payload = {
        "eligibility": "INELIGIBLE",
        "proposal": {
            "document_type": "PROPOSAL_REQUEST",
            "draft_status": "READY",
            "source_attachment_names": ["사업계획서.hwp"],
            "template_sections": ["사업 개요"],
            "draft_sections": [{"title": "사업 개요", "body": "초안입니다."}],
            "preparation": {"meeting_agenda": []},
        },
        "opportunity": {
            "dimensions": [{"type": "COMPANY_FIT", "score": 90}],
        },
    }

    _normalize_base_proposal(payload)

    assert payload["proposal"]["draft_status"] == ProposalDraftStatus.NOT_RECOMMENDED
    assert payload["proposal"]["source_attachment_names"] == []
    assert payload["proposal"]["template_sections"] == []
    assert payload["proposal"]["draft_sections"] == []
    assert payload["proposal"]["preparation"] is None


def test_normalizes_recommendation_after_template_promotes_document_type() -> None:
    draft = analysis(
        Favorability.NOT_APPLICABLE,
        opportunity_assessment=opportunity(company_fit=60),
    ).draft
    draft.proposal.document_type = ProposalDocumentType.GENERAL_NOTICE
    draft.proposal.draft_status = ProposalDraftStatus.NOT_APPLICABLE
    candidate = AgentAnalysis(draft=draft, used_tools=[], model_name="test")

    _normalize_proposal_document_type(document_with_application_template(), candidate)
    _normalize_proposal_recommendation(candidate)

    assert candidate.draft.proposal.document_type == ProposalDocumentType.PROPOSAL_REQUEST
    assert candidate.draft.proposal.draft_status == ProposalDraftStatus.NOT_RECOMMENDED
    assert candidate.draft.proposal.preparation is None


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
    titles = proposal_titles or ["우리 회사와 연결되는 부분", "이 공고에서 중요하게 볼 점"]
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


def test_graph_normalizes_notice_with_application_template_as_proposal_request() -> None:
    runner = SequencedRunner(
        [
            analysis(
                Favorability.NOT_APPLICABLE,
                used_tools=[
                    "get_document_content",
                    "get_attachment_texts",
                    "get_company_profile",
                ],
            )
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document_with_application_template()))

    assert runner.call_count == 1
    assert result.proposal.document_type == ProposalDocumentType.PROPOSAL_REQUEST
    assert result.proposal.draft_status == ProposalDraftStatus.REVIEW_REQUIRED
    assert "신청서식 또는 사업계획서 양식" in result.proposal.draft_reason


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
    assert runner.feedbacks[1] == "이전 분석 시도가 실패했습니다: AI 모델 응답 시간이 초과되었습니다."
    assert result.detection_id == 10
    assert result.importance == DocumentImportance.HIGH
    assert result.favorable_or_not == Favorability.NOT_APPLICABLE
    assert len(result.opportunity.dimensions) == 4


def test_graph_normalizes_new_document_favorability_without_retry() -> None:
    runner = SequencedRunner(
        [
            analysis(Favorability.FAVORABLE),
            analysis(Favorability.NOT_APPLICABLE),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert result.favorable_or_not == Favorability.NOT_APPLICABLE
    assert runner.call_count == 1


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
                proposal_titles=["우리 회사와 연결되는 부분", "이 공고에서 중요하게 볼 점"],
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


def test_graph_normalizes_proposal_sections_without_retry() -> None:
    runner = SequencedRunner(
        [
            analysis(Favorability.NOT_APPLICABLE, proposal_titles=["1. 접점", "7. 최종"]),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert [section.title for section in result.proposal.sections] == [
        "우리 회사와 연결되는 부분",
        "이 공고에서 중요하게 볼 점",
    ]
    assert runner.call_count == 1
    assert "공고의 주요 특징에 대한 분석이 충분히 생성되지 않았습니다." in (
        result.proposal.sections[1].body
    )


def test_updated_document_normalizes_missing_favorability_without_retry() -> None:
    runner = SequencedRunner([
        analysis(
            Favorability.NOT_APPLICABLE,
            used_tools=[
                "get_document_content",
                "get_company_profile",
                "compare_previous_version",
                "get_previous_analysis",
            ],
            proposal_titles=["우리 회사와 연결되는 부분", "이 공고에서 중요하게 볼 점"],
        )
    ])
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document("UPDATED_DOCUMENT")))

    assert runner.call_count == 1
    assert result.favorable_or_not == Favorability.REVIEW_REQUIRED


def test_low_domain_fit_requires_conservative_proposal_sections() -> None:
    conservative_titles = [
        "우리 회사와 연결되는 부분",
        "이 공고에서 중요하게 볼 점",
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
    assert runner.call_count == 1


def test_unchanged_document_requires_neutral_impact() -> None:
    runner = SequencedRunner(
        [
            analysis(
                Favorability.NEUTRAL,
                proposal_titles=["우리 회사와 연결되는 부분", "이 공고에서 중요하게 볼 점"],
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
            ("우리 회사와 연결되는 부분", "이 공고에서 중요하게 볼 점"),
        ),
        (
            AnalysisChangeType.UPDATED_DOCUMENT,
            ("우리 회사와 연결되는 부분", "이 공고에서 중요하게 볼 점"),
        ),
        (
            AnalysisChangeType.UNCHANGED_DOCUMENT,
            ("우리 회사와 연결되는 부분", "이 공고에서 중요하게 볼 점"),
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


def test_graph_normalizes_high_importance_without_company_relevance() -> None:
    runner = SequencedRunner(
        [
            analysis(
                Favorability.NOT_APPLICABLE,
                opportunity_assessment=opportunity(company_fit=35),
            ),
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert result.importance == DocumentImportance.NORMAL
    assert runner.call_count == 1


def test_system_prompt_calibrates_importance_and_opportunity_scores() -> None:
    assert "마감일이 임박했다는 사실만으로 HIGH" in SYSTEM_PROMPT
    assert "핵심 산업: 반도체·디스플레이·철강과 직접 일치 40점" in SYSTEM_PROMPT
    assert "단순 모니터링·관제·데이터 수집·시각화·플랫폼 통합·시스템 운영 0점" in SYSTEM_PROMPT
    assert "직접 경제가치: 계약·지원 금액과 회사 수혜가 명시됨 35점" in SYSTEM_PROMPT
    assert "신청·입찰 자격: 회사가 직접 충족함이 확인됨 30점" in SYSTEM_PROMPT
    assert "3~5일: 90점" in SYSTEM_PROMPT
    assert "AI 과업이 명시되지 않으면 COMPANY_FIT은 40점을 넘기지 않습니다" in SYSTEM_PROMPT
    assert "핵심 산업의 직접 일치와 제조 AI 에이전트 과업" in SYSTEM_PROMPT
    assert "단순히 해당 기관이나 기업이 자산·설비를 보유한다는 이유" in SYSTEM_PROMPT
    assert (
        "COMPANY_FIT이 40점 이하이면 현재 문서를 사업화 기회로 확장하지 않습니다" in SYSTEM_PROMPT
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
                        "신청 마감까지 남은 5일이므로 즉시 참여 여부를 결정할 필요가 있습니다."
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


def test_graph_normalizes_urgency_when_deadline_marker_is_missing() -> None:
    runner = SequencedRunner([
        analysis(
            Favorability.NOT_APPLICABLE,
            opportunity_assessment=opportunity(
                urgency=80,
                urgency_reason="담당자가 공고 내용을 빠르게 검토해야 합니다.",
            ),
        )
    ])
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    urgency = result.opportunity.dimensions[3]
    assert runner.call_count == 1
    assert urgency.score == 10
    assert "기한 미확인" in urgency.reason


def test_graph_marks_expired_application_as_ineligible() -> None:
    expired = analysis(
        Favorability.NOT_APPLICABLE,
        opportunity_assessment=opportunity(
            urgency=20,
            urgency_reason="신청 마감일은 2026년 4월 20일이며 현재는 마감 지남 상태입니다.",
        ),
    )
    runner = SequencedRunner([expired])
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=1)

    result = asyncio.run(workflow.analyze(document()))

    assert result.eligibility == Eligibility.INELIGIBLE
    urgency = next(
        dimension
        for dimension in result.opportunity.dimensions
        if dimension.type == OpportunityDimensionType.URGENCY
    )
    assert urgency.score == 0


def test_graph_recalculates_expired_deadline_instead_of_trusting_remaining_days() -> None:
    expired = analysis(
        Favorability.NOT_APPLICABLE,
        opportunity_assessment=opportunity(
            urgency=100,
            urgency_reason="신청 마감일은 2026년 8월 27일이며 남은 0일이므로 즉시 대응합니다.",
        ),
    )
    runner = SequencedRunner([expired])
    workflow = DocumentAnalysisWorkflow(
        runner=runner,
        max_attempts=1,
        analysis_date=date(2026, 9, 2),
    )

    result = asyncio.run(workflow.analyze(document()))

    urgency = next(
        dimension
        for dimension in result.opportunity.dimensions
        if dimension.type == OpportunityDimensionType.URGENCY
    )
    assert urgency.score == 0
    assert "마감 지남" in urgency.reason
    assert result.eligibility == Eligibility.INELIGIBLE


@pytest.mark.parametrize(
    "reason",
    [
        "마감일 없음(선정결과 공고)이며 회사 행동 없음입니다.",
        ("신청·제출 기한이나 회사가 수행해야 할 행동이 명시되어 있지 않습니다. 남은 0일입니다."),
    ],
)
def test_graph_normalizes_urgency_to_zero_when_company_has_no_action(
    reason: str,
) -> None:
    runner = SequencedRunner(
        [
            analysis(
                Favorability.NOT_APPLICABLE,
                opportunity_assessment=opportunity(
                    urgency=100,
                    urgency_reason=reason,
                ),
            )
        ]
    )
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=2)

    result = asyncio.run(workflow.analyze(document()))

    assert runner.call_count == 1
    assert result.opportunity.dimensions[3].score == 0


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


def test_proposal_request_with_matching_company_and_template_accepts_draft() -> None:
    draft = analysis(Favorability.NOT_APPLICABLE).draft
    draft.proposal = ProposalStrategy(
        sections=draft.proposal.sections,
        document_type=ProposalDocumentType.PROPOSAL_REQUEST,
        draft_status=ProposalDraftStatus.READY,
        draft_reason="제안서 양식과 회사 핵심 산업 및 제조 AI 과업의 직접 연관성이 확인됩니다.",
        source_attachment_names=["사업계획서 양식.hwpx"],
        template_sections=["사업 개요", "추진 계획"],
        draft_sections=[
            ProposalSection(
                title="사업 개요",
                body="제조 현장의 의사결정을 지원하는 AI 에이전트 사업을 제안합니다.",
            ),
            ProposalSection(
                title="추진 계획",
                body="확인된 공고 일정에 맞춰 분석과 실증 단계를 순차적으로 수행합니다.",
            ),
        ],
    )

    validated = AnalysisDraft.model_validate(draft.model_dump())

    assert validated.proposal.draft_status == ProposalDraftStatus.READY
    assert validated.proposal.source_attachment_names == ["사업계획서 양식.hwpx"]


def test_proposal_request_rejects_draft_title_outside_confirmed_outline() -> None:
    draft = analysis(Favorability.NOT_APPLICABLE).draft
    draft.proposal = ProposalStrategy(
        sections=draft.proposal.sections,
        document_type=ProposalDocumentType.PROPOSAL_REQUEST,
        draft_status=ProposalDraftStatus.READY,
        draft_reason="제안서 양식과 회사 핵심 산업 및 제조 AI 과업의 직접 연관성이 확인됩니다.",
        source_attachment_names=["사업계획서 양식.hwpx"],
        template_sections=["사업 개요", "추진 계획", "성과 활용"],
        draft_sections=[
            ProposalSection(
                title="사업 개요", body="제조 AI 에이전트를 적용하는 사업을 제안합니다."
            ),
            ProposalSection(title="확정되지 않은 항목", body="분석과 실증을 수행합니다."),
        ],
    )

    with pytest.raises(ValueError, match="확정된 전체 목차"):
        AnalysisDraft.model_validate(draft.model_dump())


def test_proposal_request_rejects_internal_confirmation_marker() -> None:
    draft = analysis(Favorability.NOT_APPLICABLE).draft
    draft.proposal = ProposalStrategy(
        sections=draft.proposal.sections,
        document_type=ProposalDocumentType.PROPOSAL_REQUEST,
        draft_status=ProposalDraftStatus.READY,
        draft_reason="제안서 양식과 회사 핵심 산업 및 제조 AI 과업의 직접 연관성이 확인됩니다.",
        source_attachment_names=["사업계획서 양식.hwpx"],
        template_sections=["Ⅰ. 수행계획 > 1. 과제 개요 > 1) 산업 개요"],
        draft_sections=[
            ProposalSection(
                title="Ⅰ. 수행계획 > 1. 과제 개요 > 1) 산업 개요",
                body="반도체 제조 AI 사업을 제안합니다. [회사 확인 필요: 세부 목표 수치]",
            )
        ],
    )

    with pytest.raises(ValueError, match="자연스러운 확인 안내 문장"):
        AnalysisDraft.model_validate(draft.model_dump())


def test_proposal_request_rejects_draft_for_low_company_fit() -> None:
    with pytest.raises(ValueError, match="제안서 작성을 권장할 수 없습니다"):
        AnalysisDraft(
            summary="회사 핵심 산업과 직접 관련성이 낮은 제안서 제출 사업 공고입니다.",
            key_points=["사업계획서 양식을 제출해야 합니다."],
            importance=DocumentImportance.LOW,
            reason="회사의 핵심 산업과 제조 AI 에이전트 과업이 직접 일치하지 않습니다.",
            eligibility=Eligibility.REVIEW_REQUIRED,
            favorable_or_not=Favorability.NOT_APPLICABLE,
            proposal=ProposalStrategy(
                sections=[
                    ProposalSection(
                        title="핵심 판단", body="현재 회사와의 직접적인 사업 연관성이 낮습니다."
                    )
                ],
                document_type=ProposalDocumentType.PROPOSAL_REQUEST,
                draft_status=ProposalDraftStatus.READY,
                draft_reason="첨부파일에 사업계획서 작성 양식이 포함되어 있습니다.",
                source_attachment_names=["사업계획서.pdf"],
                template_sections=["사업 개요"],
                draft_sections=[
                    ProposalSection(title="사업 개요", body="확인되지 않은 사업 초안입니다.")
                ],
            ),
            opportunity=opportunity(company_fit=35),
        )


@pytest.mark.parametrize("include_unknowns", [False, True])
def test_notice_insights_remove_unknowns_section_and_preserve_order(include_unknowns: bool) -> None:
    titles = ["우리 회사와 연결되는 부분", "이 공고에서 중요하게 볼 점"]
    input_titles = titles + (["현재 정보로 확인되지 않은 부분"] if include_unknowns else [])
    candidate = analysis(Favorability.NOT_APPLICABLE, proposal_titles=list(reversed(input_titles)))
    original_bodies = {section.title: section.body for section in candidate.draft.proposal.sections}
    runner = SequencedRunner([candidate])
    result = asyncio.run(DocumentAnalysisWorkflow(runner=runner, max_attempts=2).analyze(document()))
    assert [section.title for section in result.proposal.sections] == titles
    assert all(section.body == original_bodies[section.title] for section in result.proposal.sections)
    assert runner.call_count == 1


def test_notice_insights_do_not_relabel_legacy_strategy() -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE, proposal_titles=["핵심 판단", "즉시 실행"])
    legacy_bodies = {section.body for section in candidate.draft.proposal.sections}
    runner = SequencedRunner([candidate])
    result = asyncio.run(DocumentAnalysisWorkflow(runner=runner, max_attempts=2).analyze(document()))
    assert all(section.body not in legacy_bodies for section in result.proposal.sections)
    assert len(result.proposal.sections) == 2


def test_notice_insights_preserve_detailed_explanations_without_summary() -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    detailed = "회사에서 확인된 제조 데이터 분석 역량은 공고의 현장 데이터 처리 요구와 관련됩니다. " * 9
    detailed += "\n\n다만 현장 적용 경험과 신청 자격은 별도로 확인해야 합니다."
    candidate.draft.proposal.sections[0].body = detailed
    candidate.draft.proposal.sections.append(ProposalSection(title="공고 해석", body="과거 형식의 상단 요약 문장입니다."))
    runner = SequencedRunner([candidate])
    result = asyncio.run(DocumentAnalysisWorkflow(runner=runner, max_attempts=2).analyze(document()))
    assert len(detailed) > 260
    assert result.proposal.sections[0].body == detailed
    assert [section.title for section in result.proposal.sections] == ["우리 회사와 연결되는 부분", "이 공고에서 중요하게 볼 점"]


@pytest.mark.parametrize("bad_body", [
    "중요한 조건을 확인했습니다. 세부 산출 근거나 추",
    "핵심 판단: 회사 기술과 연결됩니다. 공고 근거: 실증이 필요합니다.",
])
def test_notice_insights_retry_incomplete_or_labeled_text(bad_body: str) -> None:
    invalid = analysis(Favorability.NOT_APPLICABLE)
    invalid.draft.proposal.sections[1].body = bad_body
    valid = analysis(Favorability.NOT_APPLICABLE)
    runner = SequencedRunner([invalid, valid])
    result = asyncio.run(DocumentAnalysisWorkflow(runner=runner, max_attempts=2).analyze(document()))
    assert runner.call_count == 2
    assert "공고 포인트" in runner.feedbacks[1]
    assert result.proposal.sections[1].body == valid.draft.proposal.sections[1].body


def test_general_analysis_reads_both_profiles_and_final_result_retains_demo_flag() -> None:
    runner = LangChainAnalysisRunner.__new__(LangChainAnalysisRunner)
    runner._settings = SimpleNamespace(
        max_text_chars=20_000, timeout_seconds=5, model_name="mock-model",
    )
    runner._evidence_agent = SimpleNamespace(collect=collect_all_evidence)
    draft = analysis(Favorability.NOT_APPLICABLE).draft
    draft.proposal.sections[0].body = (
        "데모 가정에서는 우리 회사는 GPU 한 대를 두 팀이 공유하고 있습니다."
    )
    runner._assess_legal_risks = AsyncMock(return_value=draft.comparison_summary.legal_risks)
    runner._analysis_model = AsyncMock()
    runner._analysis_model.ainvoke.return_value = draft.model_dump()
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=1)

    generated = asyncio.run(workflow.analyze(document()))

    messages = runner._analysis_model.ainvoke.call_args.args[0]
    assert "BISTelligence" in messages[1]["content"]
    assert "SYNTHETIC_DEMO" in messages[1]["content"]
    assert "DEMO-NEED-GPU" in messages[1]["content"]
    assert generated.proposal.uses_demo_profile is True
    assert generated.proposal.sections[0].body == (
        "우리 회사는 GPU 한 대를 두 팀이 공유하고 있습니다."
    )
    assert generated.model_dump(by_alias=True)["proposal"]["usesDemoProfile"] is True


@pytest.mark.parametrize("deadline", ["2026-04-20 11:00", "2026-03-19~2026-04-20"])
def test_comparison_deadline_closes_notice_even_when_urgency_omits_date(deadline) -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.comparison_summary.application_deadline = deadline
    candidate.draft.proposal.document_type = ProposalDocumentType.PROPOSAL_REQUEST
    runner = SequencedRunner([candidate])
    generated = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=1, analysis_date=date(2026, 9, 8),
    ).analyze(document()))
    assert generated.eligibility == Eligibility.INELIGIBLE
    assert generated.proposal.draft_status == ProposalDraftStatus.NOT_RECOMMENDED
    assert "접수기한이 지나" in generated.proposal.draft_reason
    assert generated.proposal.sections[1].body.startswith("접수가 종료된 공고")
    assert runner.call_count == 1


@pytest.mark.parametrize("body", [
    "연구책임자 발표가 2026.4.23 예정이므로 발표 준비와 증빙 확보가 중요합니다.",
    "회사는 마감까지 신청서류를 제출해야 합니다.",
    "현재 접수가 가능하므로 신청 준비가 필요합니다.",
])
def test_expired_notice_repairs_current_application_and_presentation_advice(body) -> None:
    invalid = analysis(Favorability.NOT_APPLICABLE)
    valid = analysis(Favorability.NOT_APPLICABLE)
    for candidate in [invalid, valid]:
        candidate.draft.comparison_summary.application_deadline = "2026-04-20 11:00"
    invalid.draft.proposal.sections[1].body = body
    valid.draft.proposal.sections[1].body = (
        "접수가 종료되어 현재 신규 신청은 불가능합니다. "
        "우리 회사는 GPU 한 대를 공유하지만 이 사업은 GPU 대여 지원 공고가 아닙니다."
    )
    runner = SequencedRunner([invalid, valid])
    generated = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=2, analysis_date=date(2026, 9, 8),
    ).analyze(document()))
    assert runner.call_count == 2
    assert "접수 종료" in runner.feedbacks[1]
    assert generated.proposal.sections[1].body == valid.draft.proposal.sections[1].body


@pytest.mark.parametrize("body", [
    "발표평가는 2026.4.23에 진행될 예정이었습니다. 현재 신규 신청은 불가능합니다.",
    "차년도 공고가 확인되면 신청 준비 범위를 다시 판단할 필요가 있습니다.",
    "현재 접수가 종료되어 발표 준비는 필요하지 않습니다.",
])
def test_expired_notice_keeps_historical_or_explicitly_future_round_context(body) -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.comparison_summary.application_deadline = "2026-04-20"
    candidate.draft.proposal.sections[1].body = body
    runner = SequencedRunner([candidate])
    generated = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=1, analysis_date=date(2026, 9, 8),
    ).analyze(document()))
    assert generated.eligibility == Eligibility.INELIGIBLE
    assert runner.call_count == 1


def test_open_notice_keeps_current_preparation_advice() -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.comparison_summary.application_deadline = "2026-11-04"
    body = "발표 준비와 증빙 확보가 중요합니다."
    candidate.draft.proposal.sections[1].body = body
    generated = asyncio.run(DocumentAnalysisWorkflow(
        runner=SequencedRunner([candidate]), max_attempts=1, analysis_date=date(2026, 9, 8),
    ).analyze(document()))
    assert generated.eligibility != Eligibility.INELIGIBLE
    assert generated.proposal.sections[1].body == body


def test_expired_bad_advice_is_not_published_when_repair_budget_is_exhausted() -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.comparison_summary.application_deadline = "2026-04-20"
    candidate.draft.proposal.sections[1].body = "발표 준비가 중요합니다."
    runner = SequencedRunner([candidate, candidate])
    workflow = DocumentAnalysisWorkflow(
        runner=runner, max_attempts=2, analysis_date=date(2026, 9, 8),
    )
    result = asyncio.run(workflow.analyze(document()))
    assert "발표 준비가 중요합니다." not in result.proposal.sections[1].body
    assert "생략" in result.proposal.sections[1].body
    assert result.eligibility == Eligibility.INELIGIBLE
    assert runner.call_count == 2


@pytest.mark.parametrize("body", [
    "당시 신청서류 제출 준비가 필요했습니다.",
    "이 공고는 신청서류 제출 준비가 중요했던 수요조사입니다.",
    "공고 당시 발표평가가 예정되어 있었습니다.",
    "신청 자격과 평가 기준을 확인해야 합니다.",
    "제출한 수요조사는 2027년 투자 계획 수립에 활용될 예정입니다.",
    "접수는 불가능하며 신청 준비도 필요하지 않습니다.",
    "차년도 공고가 확인되면, 신청 준비가 필요합니다.",
    "현재 접수는 종료됐고 차년도 공고가 확인되면 신청 준비가 필요합니다.",
    "발표평가는 2026.9.15에 진행될 예정입니다.",
    "발표평가는 2026.9.9에 진행될 예정입니다.",
])
def test_expired_notice_preserves_past_facts_and_current_review(body: str) -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.comparison_summary.application_deadline = "2026-08-28"
    candidate.draft.proposal.sections[0].body = body
    runner = SequencedRunner([candidate])

    generated = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=2, analysis_date=date(2026, 9, 9),
    ).analyze(document()))

    assert generated.proposal.sections[0].body == body
    assert generated.eligibility == Eligibility.INELIGIBLE
    assert runner.call_count == 1


@pytest.mark.parametrize("field", ["summary", "key_points[0]", "proposal.sections[0].body"])
def test_expired_notice_feedback_identifies_text_and_dates_without_logging_text(
    field: str, caplog: pytest.LogCaptureFixture, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(logging.getLogger("app"), "propagate", True)
    invalid = analysis(Favorability.NOT_APPLICABLE)
    valid = analysis(Favorability.NOT_APPLICABLE)
    for candidate in [invalid, valid]:
        candidate.draft.comparison_summary.application_deadline = "2026-08-28"
    bad_text = "투자수요 조사 신청을 위해 지금 신청서류를 제출해야 합니다."
    if field == "summary":
        invalid.draft.summary = bad_text
    elif field == "key_points[0]":
        invalid.draft.key_points[0] = bad_text
    else:
        invalid.draft.proposal.sections[0].body = bad_text
    runner = SequencedRunner([invalid, valid])

    asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=2, analysis_date=date(2026, 9, 9),
    ).analyze(document()))

    feedback = runner.feedbacks[1]
    assert feedback is not None
    assert field in feedback
    assert bad_text in feedback
    assert "2026-08-28" in feedback
    assert "2026-09-09" in feedback
    assert "공고 분석 업무 검증 실패" in caplog.text
    assert field in caplog.text
    assert bad_text not in caplog.text


@pytest.mark.parametrize("body", [
    "신청 자격에는 문제가 없지만 지금 신청서류를 제출해야 합니다.",
    "현재 접수는 불가능합니다. 그래도 신청 준비가 필요합니다.",
    "차년도 공고도 참고하되 이번 신청서류를 제출해야 합니다.",
    "제출 서류를 준비해야 합니다.",
    "지금 신청할 수 있습니다.",
    "차년도 공고가 확인되면 신청 준비가 필요하며, 지금도 신청할 수 있습니다.",
])
def test_expired_notice_removes_current_actions_and_preserves_analysis(
    body: str, caplog: pytest.LogCaptureFixture, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(logging.getLogger("app"), "propagate", True)
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.comparison_summary.application_deadline = "2026-08-28"
    candidate.draft.proposal.sections[0].body = body
    runner = SequencedRunner([candidate, candidate])

    result = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=2, analysis_date=date(2026, 9, 9),
    ).analyze(document()))

    assert runner.call_count == 2
    assert result.eligibility == Eligibility.INELIGIBLE
    assert "생략" in result.proposal.sections[0].body
    assert body not in result.proposal.sections[0].body
    assert "일부 설명 생략 후 분석 보존" in caplog.text
    assert body not in caplog.text


@pytest.mark.parametrize("body", [
    "회사의 제조 AI 기술과 공고가 연결됩니다. 다만 세부 참여 조",
    "핵심 판단: 회사 기술과 연결됩니다. 공고 근거: 실증이 필요합니다.",
])
def test_exhausted_narrative_repair_preserves_other_fields(body: str) -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.proposal.sections[0].body = body
    original = candidate.draft.model_dump()
    runner = SequencedRunner([candidate, candidate])

    result = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=2,
    ).analyze(document()))

    assert runner.call_count == 2
    assert "생략" in result.proposal.sections[0].body
    assert body not in result.proposal.sections[0].body
    assert result.summary == original["summary"]
    assert result.key_points == original["key_points"]
    assert result.comparison_summary.model_dump() == original["comparison_summary"]
    assert result.opportunity.model_dump() == original["opportunity"]
    assert result.proposal.sections[1].body == original["proposal"]["sections"][1]["body"]
    assert result.used_tools == candidate.used_tools
    # Building the retained result must not alter the model candidate used for retry feedback.
    assert candidate.draft.proposal.sections[0].body == body


def test_exhausted_expired_repair_covers_summary_points_and_all_sections() -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.comparison_summary.application_deadline = "2026-08-28"
    bad_text = "이 사업에 참여하려면 현재 신청서류를 제출해야 합니다."
    candidate.draft.summary = bad_text
    candidate.draft.key_points = [bad_text, "사업의 목적은 제조 기술의 고도화입니다."]
    for section in candidate.draft.proposal.sections:
        section.body = bad_text
    runner = SequencedRunner([candidate])

    result = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=1, analysis_date=date(2026, 9, 9),
    ).analyze(document()))

    assert runner.call_count == 1
    assert bad_text not in result.model_dump_json()
    assert "생략" in result.summary
    assert "사업의 목적은 제조 기술의 고도화입니다." in result.key_points
    assert all("생략" in section.body for section in result.proposal.sections)
    assert result.eligibility == Eligibility.INELIGIBLE
    urgency = next(
        item for item in result.opportunity.dimensions
        if item.type == OpportunityDimensionType.URGENCY
    )
    assert urgency.score == 0
    assert result.comparison_summary.application_deadline == "2026-08-28"


@pytest.mark.parametrize("failure", [TimeoutError(), RuntimeError("일시 모델 호출 실패")])
def test_retry_model_failure_keeps_previously_validated_partial_result(failure: Exception) -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.proposal.sections[0].body = "회사 기술과의 접점을 설명하다가 문장 중"
    runner = SequencedRunner([candidate, failure])

    result = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=2,
    ).analyze(document()))

    assert runner.call_count == 2
    assert result.summary == candidate.draft.summary
    assert "생략" in result.proposal.sections[0].body
    assert result.used_tools == candidate.used_tools


@pytest.mark.parametrize("invalid_kind", ["missing_tools", "invalid_schema"])
def test_narrative_fallback_never_bypasses_evidence_or_schema(invalid_kind: str) -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.proposal.sections[0].body = "공고와 회사 기술을 검토하던 중 문장 중"
    if invalid_kind == "missing_tools":
        candidate.used_tools.clear()
    else:
        candidate.draft.reason = ""
    runner = SequencedRunner([candidate, candidate])

    with pytest.raises(AnalysisWorkflowError):
        asyncio.run(DocumentAnalysisWorkflow(
            runner=runner, max_attempts=2,
        ).analyze(document()))

    assert runner.call_count == 2


def test_retained_partial_result_is_scoped_to_one_document() -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.proposal.sections[0].body = "공고와 회사 기술을 검토하던 중 문장 중"
    runner = SequencedRunner([candidate, RuntimeError("모델 호출 실패")])
    workflow = DocumentAnalysisWorkflow(runner=runner, max_attempts=1)

    assert "생략" in asyncio.run(workflow.analyze(document())).proposal.sections[0].body
    with pytest.raises(AnalysisWorkflowError, match="모델 호출 실패"):
        asyncio.run(workflow.analyze(document().model_copy(update={"version_id": 31})))


def test_exhausted_missing_closed_context_preserves_analysis_with_bounded_notice() -> None:
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.comparison_summary.application_deadline = "2026-08-28"
    candidate.draft.proposal.sections[1].body = "기술 연관성을 검토합니다. " * 65
    runner = SequencedRunner([candidate])

    result = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=1, analysis_date=date(2026, 9, 9),
    ).analyze(document()))

    assert result.proposal.sections[1].body.startswith("접수가 종료된 공고")
    assert "생략" in result.proposal.sections[1].body
    assert len(result.proposal.sections[1].body) <= 1000
    assert result.eligibility == Eligibility.INELIGIBLE


@pytest.mark.parametrize("retry_timeout", [False, True])
def test_ten_document_batch_retains_notice_with_repeated_narrative_failure(
    retry_timeout: bool,
) -> None:
    class BatchRunner:
        def __init__(self) -> None:
            self.attempts: dict[int, int] = {}

        async def analyze(
            self, item: AnalysisDocumentRequest, feedback: str | None = None,
        ) -> AgentAnalysis:
            await asyncio.sleep(0)
            attempt = self.attempts.get(item.detection_id, 0) + 1
            self.attempts[item.detection_id] = attempt
            if item.detection_id == 8 and attempt == 2 and retry_timeout:
                raise TimeoutError()
            candidate = analysis(Favorability.NOT_APPLICABLE)
            if item.detection_id == 8:
                candidate.draft.comparison_summary.application_deadline = "2026-08-28"
                candidate.draft.proposal.sections[0].body = "지금 신청할 수 있습니다."
            return candidate

    runner = BatchRunner()
    workflow = DocumentAnalysisWorkflow(
        runner=runner, max_attempts=2, analysis_date=date(2026, 9, 9),
    )
    documents = [
        document().model_copy(update={
            "detection_id": identifier, "document_id": identifier, "version_id": identifier,
        })
        for identifier in range(1, 11)
    ]

    results, failures = asyncio.run(_analyze_documents(workflow, documents, concurrency=2))

    assert failures == []
    assert [item.detection_id for item in results] == list(range(1, 11))
    assert runner.attempts[8] == 2
    assert all(count == 1 for identifier, count in runner.attempts.items() if identifier != 8)
    for item in results:
        body = item.proposal.sections[0].body
        assert ("생략" in body) == (item.detection_id == 8)
        assert "지금 신청할 수 있습니다." not in body


def test_updated_attachment_evidence_and_change_focus_reach_model_and_saved_insight() -> None:
    payload = document("UPDATED_DOCUMENT").model_dump(by_alias=True)
    payload["contentText"] = payload["previousVersion"]["contentText"] = "자세한 조건은 첨부를 확인합니다."
    payload["previousVersion"]["attachments"] = [
        {"attachmentId": 1, "fileName": "공고문.pdf", "extractedText": "필수 서류는 신청서입니다."}
    ]
    payload["attachments"] = [
        {"attachmentId": 2, "fileName": "공고문.pdf", "extractedText": "필수 서류는 신청서와 납세증명서입니다."}
    ]
    request = AnalysisDocumentRequest.model_validate(payload)
    runner = LangChainAnalysisRunner.__new__(LangChainAnalysisRunner)
    runner._settings = SimpleNamespace(max_text_chars=20_000, timeout_seconds=5, model_name="mock-model")
    runner._evidence_agent = SimpleNamespace(collect=collect_all_evidence)
    draft = analysis(Favorability.REVIEW_REQUIRED).draft
    insight = (
        "이전에는 신청서만 요구했지만 수정 공고에는 납세증명서가 추가되었습니다. "
        "제출 증빙의 범위가 늘어났으며 현재 회사 정보에서는 해당 증명서의 유효성이 확인되지 않습니다."
    )
    draft.proposal.sections[1].body = insight
    runner._assess_legal_risks = AsyncMock(return_value=draft.comparison_summary.legal_risks)
    runner._analysis_model = AsyncMock()
    runner._analysis_model.ainvoke.return_value = draft.model_dump()

    generated = asyncio.run(DocumentAnalysisWorkflow(runner=runner, max_attempts=1).analyze(request))

    prompt = runner._analysis_model.ainvoke.call_args.args[0][1]["content"]
    assert "변경 전 → 변경 후 → 우리 회사에 미치는 영향" in prompt
    assert "-필수 서류는 신청서입니다." in prompt
    assert "+필수 서류는 신청서와 납세증명서입니다." in prompt
    assert "attachmentComparison" in prompt
    assert runner._analysis_model.ainvoke.await_count == 1
    assert generated.proposal.sections[1].body == insight


@pytest.mark.parametrize("source_limit", [40_000, 16_000])
@pytest.mark.parametrize("failure_kind", ["native", "sdk", "transport", "validation"])
def test_retry_shrinks_sources_and_guides_complete_output_only_for_timeouts(
    source_limit: int, failure_kind: str,
) -> None:
    import json

    import httpx
    from openai import APITimeoutError

    from app.domains.analysis.retry_policy import COMPACT_RETRY_INSTRUCTIONS

    errors = {
        "native": TimeoutError("nonempty timeout message"),
        "sdk": APITimeoutError(request=httpx.Request("POST", "https://example.org/model")),
        "transport": httpx.ReadTimeout("Read timed out"),
        "validation": ValueError("필드 형식을 확인하세요"),
    }
    request = document("UPDATED_DOCUMENT").model_copy(update={
        "content_text": "접수 마감은 2026년 9월 30일 18시입니다.\n"
        + "배경 설명입니다. " * 6000
        + "\n자부담 비율은 20%이며 납세증명서가 필요합니다.",
    })
    draft = analysis(Favorability.REVIEW_REQUIRED).draft
    runner = LangChainAnalysisRunner.__new__(LangChainAnalysisRunner)
    runner._settings = SimpleNamespace(
        max_text_chars=source_limit, timeout_seconds=5, model_name="mock-model",
    )
    runner._evidence_agent = SimpleNamespace(collect=collect_all_evidence)
    runner._assess_legal_risks = AsyncMock(return_value=draft.comparison_summary.legal_risks)
    runner._analysis_model = AsyncMock()
    runner._analysis_model.ainvoke.side_effect = [errors[failure_kind], draft.model_dump()]

    generated = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=2, analysis_date=date(2026, 9, 11),
    ).analyze(request))

    calls = runner._analysis_model.ainvoke.call_args_list
    assert len(calls) == 2
    prompts = [call.args[0] for call in calls]
    assert COMPACT_RETRY_INSTRUCTIONS not in prompts[0][0]["content"]
    assert (COMPACT_RETRY_INSTRUCTIONS in prompts[1][0]["content"]) == (failure_kind != "validation")
    def input_body(messages):
        data = messages[1]["content"].split("<current_document>\n", 1)[1].split("\n</current_document>", 1)[0]
        return json.loads(data)["contentText"]
    assert len(input_body(prompts[0])) == source_limit
    assert len(input_body(prompts[1])) == (source_limit if failure_kind == "validation" else min(source_limit, 24_000))
    assert "2026년 9월 30일 18시" in input_body(prompts[1])
    assert "자부담 비율은 20%" in input_body(prompts[1])
    retry_system = prompts[1][0]["content"]
    if failure_kind != "validation":
        for essential in ("필수 제출 서류", "지원 대상과 제외 대상", "금액 단위·상한",
                          "예외·부정·제한 조건", "변경 전 → 변경 후 → 회사 영향", "400~700자",
                          "미확인 사항", "기존 필드 상한", "필수 필드를 비우지"):
            assert essential in retry_system
    assert generated.summary == draft.summary
    assert generated.key_points == draft.key_points
    assert len(generated.proposal.sections) == 2
    assert len(generated.opportunity.dimensions) == 4
    assert generated.comparison_summary == draft.comparison_summary
    assert generated.proposal.sections[1].body == draft.proposal.sections[1].body


def test_future_deadline_removes_stale_closure_before_eligibility_normalization():
    candidate = analysis(Favorability.NOT_APPLICABLE, opportunity_assessment=opportunity(
        urgency_reason="신청 마감일은 2026년 10월 1일이며 마감 지남 상태입니다."))
    candidate.draft.comparison_summary.application_deadline = "2026-10-01 18:00"
    expected_eligibility = candidate.draft.eligibility
    generated = asyncio.run(DocumentAnalysisWorkflow(
        runner=SequencedRunner([candidate]), max_attempts=1, analysis_date=date(2026, 9, 11),
    ).analyze(document()))
    urgency = next(item for item in generated.opportunity.dimensions if item.type.value == "URGENCY")
    assert urgency.reason == "신청 마감일은 2026년 10월 1일이며 분석일 기준 남은 20일입니다."
    assert generated.eligibility == expected_eligibility
    assert generated.eligibility != Eligibility.INELIGIBLE
    assert "접수기한이 지나" not in (generated.proposal.draft_reason or "")
