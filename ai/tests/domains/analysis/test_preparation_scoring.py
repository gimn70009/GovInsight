from datetime import date

from app.domains.analysis.preparation_scoring import score_preparation
from app.domains.analysis.schemas.result import (
    CompanyEvidenceLevel,
    PreparationChecklistItem,
    PreparationWorkType,
    ProposalPreparation,
    StopCriterionType,
    StrategyCapabilityMatch,
    StrategyDecision,
    StrategyGap,
    StrategyOnePage,
    StrategyStopCriterion,
)


def _preparation(item: PreparationChecklistItem) -> ProposalPreparation:
    return ProposalPreparation(
        meeting_agenda=["자격을 확인합니다.", "서류를 준비합니다.", "일정을 확정합니다."],
        eligibility_checklist=[item],
        submission_documents=[
            PreparationChecklistItem(
                title="사업계획서",
                detail="사업계획서를 새로 작성해야 합니다.",
                next_action="사업담당자가 사업계획서를 작성합니다.",
                estimated_business_days=10,
            )
        ],
        company_inputs=[
            PreparationChecklistItem(
                title="투입 인력",
                detail="투입 인력 정보가 확인되지 않았습니다.",
                next_action="사업담당자가 투입 인력을 확인합니다.",
            )
        ],
        application_deadline="2026-09-30",
        strategy=StrategyOnePage(
            decision=StrategyDecision.CONDITIONAL_GO,
            decision_reason="필수 조건을 확인한 뒤 지원을 권장합니다.",
            recommended_project="제조 데이터 활용 사업",
            recommended_participation="회사는 기술 공급기업으로 참여하는 방안을 검토합니다.",
            alternative_participation="조건이 부족하면 공동연구개발기관으로 참여합니다.",
            capability_matches=[
                StrategyCapabilityMatch(
                    confirmed_fact="회사 공개정보에서 데이터 사업 경험이 확인됩니다.",
                    strategic_interpretation="공고의 데이터 과업과 연결할 수 있습니다.",
                )
            ],
            critical_gaps=[
                StrategyGap(
                    gap="참여기관이 확인되지 않았습니다.",
                    next_action="사업담당자가 참여기관을 확보합니다.",
                    owner="사업담당자",
                    target_timing="공고 마감 전에 완료합니다.",
                    work_type=PreparationWorkType.TECHNICAL_PLANNING,
                    estimated_business_days=10,
                )
            ],
            stop_criteria=[
                StrategyStopCriterion(
                    type=StopCriterionType.OFFICIAL_REQUIREMENT,
                    condition="필수 참여기관을 확보하지 못하면 지원을 중단합니다.",
                    rationale="공고의 필수 구성 조건을 충족할 수 없습니다.",
                )
            ],
        ),
    )


def test_scores_unknown_company_evidence_conservatively() -> None:
    item = PreparationChecklistItem(
        title="컨소시엄 구성",
        detail="공개정보만으로 파트너 확보 여부를 확인할 수 없습니다.",
        next_action="사업담당자가 참여기관의 의사를 확인합니다.",
        company_evidence_level=CompanyEvidenceLevel.UNKNOWN,
        estimated_business_days=20,
    )
    preparation = _preparation(item)

    score_preparation(preparation, date(2026, 9, 1))

    assert item.readiness_score == 20
    assert item.condition_score == 0
    assert item.score_basis[0].startswith("조건 충족도 0점")


def test_back_schedules_internal_target_from_application_deadline() -> None:
    item = PreparationChecklistItem(
        title="기업 자격",
        detail="공식 증빙에서 기업 자격을 확인했습니다.",
        next_action="사업담당자가 증빙 유효기간을 재확인합니다.",
        company_evidence_level=CompanyEvidenceLevel.OFFICIAL_DOCUMENT,
    )
    preparation = _preparation(item)

    score_preparation(preparation, date(2026, 9, 1))

    gap = preparation.strategy.critical_gaps[0]
    assert gap.target_date == "2026-09-09"
    assert gap.estimated_business_days == 10
    assert "기술기획" in gap.schedule_basis
    assert "10일" in gap.schedule_basis
    assert "영업일" not in gap.schedule_basis


def test_never_schedules_internal_target_before_analysis_date() -> None:
    preparation = _preparation(
        PreparationChecklistItem(
            title="기업 자격",
            detail="기업 자격을 확인해야 합니다.",
            next_action="사업담당자가 기업 자격을 확인합니다.",
        )
    )
    preparation.application_deadline = "2026-09-08"

    score_preparation(preparation, date(2026, 9, 2))

    gap = preparation.strategy.critical_gaps[0]
    assert gap.target_date == "2026-09-02"
    assert "즉시 착수" in gap.target_timing


def test_does_not_schedule_new_actions_for_expired_notice() -> None:
    preparation = _preparation(
        PreparationChecklistItem(
            title="기업 자격",
            detail="기업 자격을 확인해야 합니다.",
            next_action="사업담당자가 기업 자격을 확인합니다.",
        )
    )
    preparation.application_deadline = "2026-08-27"

    score_preparation(preparation, date(2026, 9, 2))

    gap = preparation.strategy.critical_gaps[0]
    assert gap.target_date is None
    assert "마감일이 지나" in gap.target_timing


def test_overrides_model_days_with_fixed_work_type_policy() -> None:
    item = PreparationChecklistItem(
        title="연구개발전담부서 인정서",
        detail="인정서 보유 여부가 확인되지 않았습니다.",
        next_action="연구개발 담당자가 인정서 보유 여부를 확인합니다.",
        work_type=PreparationWorkType.CERTIFICATION,
        estimated_business_days=1,
    )
    preparation = _preparation(item)

    score_preparation(preparation, date(2026, 9, 1))

    assert item.estimated_business_days == 15


def test_uses_company_evidence_level_without_deriving_a_status() -> None:
    item = PreparationChecklistItem(
        title="회사 기본정보",
        detail="회사 공개정보에서 기본정보 일부가 확인됩니다.",
        next_action="사업담당자가 공식 증빙을 확인합니다.",
        company_evidence_level=CompanyEvidenceLevel.OFFICIAL_WEBSITE,
    )
    preparation = _preparation(item)
    preparation.company_inputs = [item]

    score_preparation(preparation, date(2026, 9, 1))

    assert item.condition_score == 65
    assert item.evidence_score == 65
    assert item.readiness_score == 72
