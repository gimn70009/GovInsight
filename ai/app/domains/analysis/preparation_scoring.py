from __future__ import annotations

from datetime import date, timedelta

from app.domains.analysis.schemas.result import (
    CompanyEvidenceLevel,
    PreparationChecklistItem,
    PreparationStatus,
    PreparationWorkType,
    ProposalPreparation,
    RequirementLevel,
)

SCORING_VERSION = "1.1"

WORK_TYPE_BUSINESS_DAYS = {
    PreparationWorkType.INTERNAL_CONFIRMATION: 2,
    PreparationWorkType.EXTERNAL_CONFIRMATION: 7,
    PreparationWorkType.DOCUMENT_ISSUANCE: 5,
    PreparationWorkType.CERTIFICATION: 15,
    PreparationWorkType.SIGNATURE_SEAL: 3,
    PreparationWorkType.BUDGET_REVIEW: 7,
    PreparationWorkType.PROPOSAL_WRITING: 10,
    PreparationWorkType.TECHNICAL_PLANNING: 10,
    PreparationWorkType.DOMESTIC_PARTNER: 15,
    PreparationWorkType.INTERNATIONAL_PARTNER: 25,
    PreparationWorkType.LEGAL_CONTRACT: 7,
    PreparationWorkType.OTHER: 5,
}

WORK_TYPE_LABELS = {
    PreparationWorkType.INTERNAL_CONFIRMATION: "회사 내부 확인",
    PreparationWorkType.EXTERNAL_CONFIRMATION: "외부기관 확인",
    PreparationWorkType.DOCUMENT_ISSUANCE: "공식 문서 발급",
    PreparationWorkType.CERTIFICATION: "인증 취득 또는 갱신",
    PreparationWorkType.SIGNATURE_SEAL: "서명 및 직인",
    PreparationWorkType.BUDGET_REVIEW: "예산 작성 및 검토",
    PreparationWorkType.PROPOSAL_WRITING: "제안서 작성",
    PreparationWorkType.TECHNICAL_PLANNING: "기술기획",
    PreparationWorkType.DOMESTIC_PARTNER: "국내 파트너 확보",
    PreparationWorkType.INTERNATIONAL_PARTNER: "해외 파트너 확보",
    PreparationWorkType.LEGAL_CONTRACT: "법무 및 계약 검토",
    PreparationWorkType.OTHER: "기타 준비",
}

_CONDITION_SCORES = {
    PreparationStatus.VERIFIED: 100,
    PreparationStatus.LIKELY: 75,
    PreparationStatus.NEEDS_CONFIRMATION: 50,
    PreparationStatus.ACTION_REQUIRED: 25,
    PreparationStatus.READY: 75,
    PreparationStatus.MISSING: 0,
    PreparationStatus.INELIGIBLE: 0,
    PreparationStatus.NOT_APPLICABLE: 100,
}

_EVIDENCE_SCORES = {
    CompanyEvidenceLevel.OFFICIAL_DOCUMENT: 100,
    CompanyEvidenceLevel.USER_CONFIRMED: 90,
    CompanyEvidenceLevel.OFFICIAL_WEBSITE: 65,
    CompanyEvidenceLevel.PUBLIC_INFORMATION: 45,
    CompanyEvidenceLevel.UNKNOWN: 0,
}


def score_preparation(preparation: ProposalPreparation, reference_date: date | None = None) -> None:
    """Apply one deterministic scoring and scheduling policy to every proposal."""
    today = reference_date or date.today()
    deadline = _parse_date(preparation.application_deadline)
    remaining_days = _business_days_between(today, deadline) if deadline else None

    for item in preparation.eligibility_checklist:
        item.estimated_business_days = WORK_TYPE_BUSINESS_DAYS[item.work_type]
        _score_item(item, remaining_days, allow_likely=True)
    for item in (*preparation.submission_documents, *preparation.company_inputs):
        item.estimated_business_days = WORK_TYPE_BUSINESS_DAYS[item.work_type]
        _score_item(item, remaining_days, allow_likely=False)

    if deadline:
        for gap in preparation.strategy.critical_gaps:
            gap.estimated_business_days = WORK_TYPE_BUSINESS_DAYS[gap.work_type]
            target = _subtract_business_days(deadline, gap.estimated_business_days + 5)
            gap.target_date = target.isoformat()
            gap.target_timing = f"{target.isoformat()}까지 완료하는 것을 권장합니다."
            gap.schedule_basis = (
                f"{WORK_TYPE_LABELS[gap.work_type]}에 {gap.estimated_business_days}일, "
                "최종 검수와 제출에 5일이 필요한 것으로 보고 주말을 제외해 "
                "공고 마감일에서 역산했습니다."
            )


def _score_item(
    item: PreparationChecklistItem,
    remaining_days: int | None,
    allow_likely: bool,
) -> None:
    condition = item.condition_score
    if condition is None:
        condition = _CONDITION_SCORES[item.status]
    evidence = _EVIDENCE_SCORES[item.company_evidence_level]
    schedule = _schedule_score(remaining_days, item.estimated_business_days)
    readiness = round(condition * 0.8 + schedule * 0.2)

    item.condition_score = condition
    item.evidence_score = evidence
    item.schedule_score = schedule
    item.readiness_score = readiness
    item.status = _status_for(item, condition, evidence, readiness, allow_likely)
    schedule_basis = (
        f"주말을 제외한 남은 {remaining_days}일과 준비 기간 "
        f"{item.estimated_business_days}일을 비교했습니다."
        if remaining_days is not None
        else f"마감일을 확인하지 못해 준비 기간 {item.estimated_business_days}일에 "
        "일정 중립 점수를 적용했습니다."
    )
    item.score_basis = [
        f"조건 충족도 {condition}점의 80%를 준비도에 반영했습니다.",
        f"일정 여유 {schedule}점의 20%를 준비도에 반영했습니다. {schedule_basis}",
        f"GovInsight 준비도 산식 {SCORING_VERSION}을 적용했습니다.",
    ]


def _schedule_score(remaining_days: int | None, estimated_days: int) -> int:
    if remaining_days is None:
        return 50
    if remaining_days <= 0:
        return 0
    return min(100, round(remaining_days / estimated_days * 100))


def _status_for(
    item: PreparationChecklistItem,
    condition: int,
    evidence: int,
    readiness: int,
    allow_likely: bool,
) -> PreparationStatus:
    if item.status == PreparationStatus.NOT_APPLICABLE:
        return PreparationStatus.NOT_APPLICABLE
    if item.status == PreparationStatus.INELIGIBLE:
        return PreparationStatus.INELIGIBLE
    if item.status in {PreparationStatus.ACTION_REQUIRED, PreparationStatus.MISSING}:
        return PreparationStatus.ACTION_REQUIRED
    if evidence == 0 or item.status == PreparationStatus.NEEDS_CONFIRMATION:
        return PreparationStatus.NEEDS_CONFIRMATION
    if condition == 100 and evidence >= 90:
        return PreparationStatus.VERIFIED
    if allow_likely and readiness >= 70 and condition >= 75:
        return PreparationStatus.LIKELY
    if item.requirement_level == RequirementLevel.MANDATORY and condition < 100:
        return PreparationStatus.ACTION_REQUIRED
    return PreparationStatus.NEEDS_CONFIRMATION


def _parse_date(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


def _business_days_between(start: date, end: date) -> int:
    if end <= start:
        return 0
    current = start
    count = 0
    while current < end:
        current += timedelta(days=1)
        if current.weekday() < 5:
            count += 1
    return count


def _subtract_business_days(start: date, business_days: int) -> date:
    current = start
    remaining = business_days
    while remaining:
        current -= timedelta(days=1)
        if current.weekday() < 5:
            remaining -= 1
    return current
