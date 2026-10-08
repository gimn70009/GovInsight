import asyncio
from datetime import date

import pytest

from app.domains.analysis.schemas.result import (
    Eligibility,
    Favorability,
    OpportunityDimensionType,
    ProposalDocumentType,
    ProposalDraftStatus,
)
from app.domains.analysis.workflow.graph import DocumentAnalysisWorkflow
from tests.domains.analysis.workflow.test_graph import (
    SequencedRunner,
    analysis,
    document,
    opportunity,
)

ANALYSIS_DATE = date(2026, 10, 8)
UNKNOWN_DEADLINE = "원문에서 신청 마감일을 확인하지 못했습니다."


def _analyze_deadline(deadline, reason, *, eligibility=Eligibility.REVIEW_REQUIRED):
    candidate = analysis(
        Favorability.NOT_APPLICABLE,
        opportunity_assessment=opportunity(urgency=90, urgency_reason=reason),
    )
    candidate.draft.comparison_summary.application_deadline = deadline
    candidate.draft.eligibility = eligibility
    candidate.draft.proposal.document_type = ProposalDocumentType.PROPOSAL_REQUEST
    candidate.draft.proposal.draft_status = ProposalDraftStatus.REVIEW_REQUIRED
    candidate.draft.key_points = ["신청 자격과 제출서류는 공고 원문을 확인해야 합니다."]
    notice = document()
    notice.content_text = f"신청 안내: {deadline}\n{reason}"
    runner = SequencedRunner([candidate])
    result = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=1, analysis_date=ANALYSIS_DATE,
    ).analyze(notice))
    assert runner.call_count == 1
    urgency = next(
        item for item in result.opportunity.dimensions
        if item.type == OpportunityDimensionType.URGENCY
    )
    return result, urgency


@pytest.mark.parametrize("reason", [
    "신청 기간은 2026-09-01부터 2026-10-30까지로 남은 22일입니다.",
    "신청 마감일은 2026-10-30이며 남은 22일입니다.",
])
def test_explicit_end_date_prevents_partial_period_from_closing_open_notice(reason):
    result, urgency = _analyze_deadline(
        "2026-09-01부터 10월 30일까지", reason,
    )

    assert result.eligibility == Eligibility.REVIEW_REQUIRED
    assert result.proposal.draft_status == ProposalDraftStatus.REVIEW_REQUIRED
    assert urgency.score == 35
    assert "2026년 10월 30일" in urgency.reason
    assert "남은 22일" in urgency.reason
    assert "마감 지남" not in urgency.reason
    assert "접수가 종료" not in result.proposal.sections[1].body


@pytest.mark.parametrize("deadline", [
    "2026년 9월 1일부터 2026-10-30까지",
    "2026-09-01부터 2026년 10월 30일까지",
    "2026. 9. 1.(화) 09:00 ~ 2026. 10. 30.(금) 18:00",
])
def test_complete_period_uses_end_date_regardless_of_date_format(deadline):
    result, urgency = _analyze_deadline(
        deadline, "신청 마감일까지 남은 5일이므로 자격 검토가 필요합니다.",
    )

    assert urgency.score == 35
    assert "남은 22일" in urgency.reason
    assert result.eligibility == Eligibility.REVIEW_REQUIRED
    assert result.proposal.draft_status == ProposalDraftStatus.REVIEW_REQUIRED


@pytest.mark.parametrize(("deadline", "reason"), [
    ("2026-09-01부터 10월 30일까지", "신청 기간은 2026-09-01부터 10월 30일까지입니다."),
    ("2026-09-01 또는 2026-10-30", "두 일정 2026-09-01과 2026-10-30을 확인해야 합니다."),
    ("2026-09-01 또는 2026-10-30", "신청 마감일은 2026-09-01이며 마감 지남 상태입니다."),
    ("2026-09-01 또는 2026-10-30", "신청 마감일은 2026-10-30이며 남은 22일입니다."),
    ("2026-09-01부터 10월 30일까지", "접수기간은 2026-09-01부터 10월 30일까지로 마감 지남입니다."),
    (UNKNOWN_DEADLINE, "분석일은 2026-09-01이며 마감 지남 상태입니다."),
    (UNKNOWN_DEADLINE, "공고일은 2026-09-01이며 마감 지남 상태입니다."),
    (UNKNOWN_DEADLINE, "행사일은 2026-09-01이며 마감 지남 상태입니다."),
    (UNKNOWN_DEADLINE, "신청 시작일은 2026-09-01이며 남은 0일입니다."),
    (UNKNOWN_DEADLINE, "신청 마감일은 2026-02-30이며 마감 지남 상태입니다."),
    (UNKNOWN_DEADLINE, "신청 접수는 마감 지남 상태입니다."),
    (UNKNOWN_DEADLINE, "평가 마감일은 2026-09-01입니다."),
    (UNKNOWN_DEADLINE, "신청 마감일은 2026-09-01이며 공고 변경으로 2026-10-30까지 연장되었습니다."),
    (UNKNOWN_DEADLINE, "접수기간: 2026-09-01 시작, 종료일 미정"),
    (UNKNOWN_DEADLINE, "접수기간: 2026-09-01 -"),
])
def test_ambiguous_or_unrelated_dates_do_not_create_application_ineligibility(deadline, reason):
    result, urgency = _analyze_deadline(deadline, reason)

    assert result.eligibility == Eligibility.REVIEW_REQUIRED
    assert result.proposal.draft_status == ProposalDraftStatus.REVIEW_REQUIRED
    assert urgency.score == 10
    assert "기한 미확인" in urgency.reason
    assert "마감 지남" not in urgency.reason
    assert "접수가 종료" not in result.proposal.sections[1].body


def test_explicit_application_deadline_can_fill_comparison_field_without_dates():
    result, urgency = _analyze_deadline(
        UNKNOWN_DEADLINE,
        "분석일은 2026-10-08이며 신청 마감일은 2026-10-30입니다.",
    )

    assert urgency.score == 35
    assert "남은 22일" in urgency.reason
    assert result.eligibility == Eligibility.REVIEW_REQUIRED


def test_confirmed_past_deadline_still_disables_proposal_generation():
    result, urgency = _analyze_deadline(
        "2026-10-07 18:00", "신청 마감일까지 남은 5일입니다.",
    )

    assert urgency.score == 0
    assert "마감 지남" in urgency.reason
    assert result.eligibility == Eligibility.INELIGIBLE
    assert result.proposal.draft_status == ProposalDraftStatus.NOT_RECOMMENDED
    assert result.proposal.sections[1].body.startswith("접수가 종료된 공고")


def test_normalized_closed_reason_remains_valid_with_its_added_analysis_date():
    first_result, first_urgency = _analyze_deadline(
        UNKNOWN_DEADLINE, "신청 마감일은 2026-10-07이며 남은 5일입니다.",
    )
    repeated_result, repeated_urgency = _analyze_deadline(
        UNKNOWN_DEADLINE, first_urgency.reason,
    )

    assert "2026년 10월 7일" in first_urgency.reason
    assert "2026년 10월 8일 분석 기준" in first_urgency.reason
    assert repeated_urgency.reason == first_urgency.reason
    for result, urgency in (
        (first_result, first_urgency), (repeated_result, repeated_urgency),
    ):
        assert urgency.score == 0
        assert result.eligibility == Eligibility.INELIGIBLE
        assert result.proposal.draft_status == ProposalDraftStatus.NOT_RECOMMENDED


def test_confirmed_future_deadline_overrides_stale_closure_reason():
    result, urgency = _analyze_deadline(
        "2026-10-30 18:00", "신청 마감일은 2026-09-01이며 마감 지남 상태입니다.",
    )

    assert urgency.score == 35
    assert "마감 지남" not in urgency.reason
    assert result.eligibility == Eligibility.REVIEW_REQUIRED
    assert result.proposal.draft_status == ProposalDraftStatus.REVIEW_REQUIRED


@pytest.mark.parametrize("deadline", ["2026-10-30", "2026-09-01부터 10월 30일까지"])
def test_date_normalization_does_not_remove_existing_eligibility_rejection(deadline):
    result, _ = _analyze_deadline(
        deadline,
        "신청 마감일은 2026-10-30이며 회사는 필수 참여 자격을 충족하지 못했습니다.",
        eligibility=Eligibility.INELIGIBLE,
    )

    assert result.eligibility == Eligibility.INELIGIBLE
    assert result.proposal.draft_status == ProposalDraftStatus.NOT_RECOMMENDED


@pytest.mark.parametrize(("reason", "expected_score"), [
    ("신청 마감까지 남은 5일이므로 검토가 필요합니다.", 90),
    ("상시 접수하며 예산 소진 시까지 선착순으로 신청을 받습니다.", 70),
    ("선정 결과 안내로 회사 행동 없음입니다.", 0),
    ("원문에 기한 미확인 상태이므로 추가 확인이 필요합니다.", 10),
])
def test_date_free_urgency_policy_remains_unchanged(reason, expected_score):
    result, urgency = _analyze_deadline(UNKNOWN_DEADLINE, reason)

    assert urgency.score == expected_score
    assert result.eligibility == Eligibility.REVIEW_REQUIRED


def test_non_application_date_does_not_remove_explicit_no_action_status():
    result, urgency = _analyze_deadline(
        UNKNOWN_DEADLINE,
        "공고일은 2026-09-01이며 선정 결과 안내로 회사 행동 없음입니다.",
    )

    assert urgency.score == 0
    assert "회사 행동 없음" in urgency.reason
    assert result.eligibility == Eligibility.REVIEW_REQUIRED
