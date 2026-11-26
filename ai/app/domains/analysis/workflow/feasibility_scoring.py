from datetime import date

from app.domains.analysis.context.evaluation_settings import EvaluationSettingsSnapshot
from app.domains.analysis.schemas.feasibility import FeasibilityEvidence
from app.domains.analysis.schemas.result import (
    Eligibility,
    OpportunityDimension,
    OpportunityDimensionType,
)

SCORING_POLICY_VERSION = 1


def score_feasibility(
    evidence: FeasibilityEvidence | None,
    settings: EvaluationSettingsSnapshot,
    eligibility: Eligibility,
    deadline: date | None,
    analysis_date: date,
) -> OpportunityDimension:
    # Old/incomplete runners cannot retain an unverified model-generated score.
    if evidence is None:
        evidence = FeasibilityEvidence.model_validate(
            {
                "eligibility": {
                    "status": "UNKNOWN",
                    "evidence": "신청 자격의 증빙을 확인하지 못했습니다.",
                },
                "technology": {
                    "status": "UNKNOWN",
                    "evidence": "보유 기술의 대응 근거를 확인하지 못했습니다.",
                },
                "experience": {
                    "status": "NONE",
                    "evidence": "인정 가능한 완료 실적을 확인하지 못했습니다.",
                },
                "staffing": {
                    "status": "UNKNOWN",
                    "evidence": "투입 가능한 인력을 확인하지 못했습니다.",
                },
                "prerequisites": {
                    "status": "UNKNOWN",
                    "partner": "UNKNOWN",
                    "evidence": "필수 서류와 파트너 조건을 확인하지 못했습니다.",
                },
            }
        )
    facts = evidence.model_copy(deep=True)
    if eligibility == Eligibility.INELIGIBLE:
        facts.eligibility.status = "UNMET"
        facts.eligibility.evidence = "공고의 신청 불가 조건이 확인되었습니다."
    elif eligibility == Eligibility.REVIEW_REQUIRED and facts.eligibility.status == "CONFIRMED":
        facts.eligibility.status = "UNKNOWN"
        facts.eligibility.evidence = "신청 자격의 추가 확인이 필요합니다."

    if not any(project.status == "COMPLETED" for project in settings.data.company.projects):
        facts.experience.status = "NONE"
        facts.experience.evidence = "회사 정보에 등록된 완료 실적이 없습니다."

    partner = facts.prerequisites.partner
    if facts.technology.status == "PARTNER" and partner in {"UNKNOWN", "UNMET"}:
        facts.technology.status = "UNKNOWN"
        facts.technology.evidence = "필수 파트너의 확보가 확인되지 않아 기술 보완도 미확인입니다."
    if partner in {"UNKNOWN", "UNMET"} and facts.prerequisites.status == "CONFIRMED":
        facts.prerequisites.status = partner
        facts.prerequisites.evidence = "필수 파트너의 확보가 확인되지 않았습니다."

    days = (deadline - analysis_date).days if deadline is not None else None
    minimum = settings.data.criteria.preparation_days
    expired = days is not None and days < 0
    insufficient = days is not None and minimum is not None and days < minimum
    if expired:
        readiness, timing = 0, "신청 마감일이 지나 현재 신청할 수 없습니다."
    elif insufficient:
        readiness = 0
        timing = (
            f"마감까지 {days}일로, 설정한 최소 준비 기간 {minimum}일보다 "
            f"{minimum - days}일 부족합니다."
        )
    elif facts.staffing.status == "UNAVAILABLE":
        readiness, timing = 0, "현재 투입 가능한 인력이 없습니다."
    elif days is None or minimum is None:
        readiness, timing = (
            5,
            "마감일 또는 최소 준비 기간이 미정이므로 준비 기간의 충족 여부를 확인해야 합니다.",
        )
    elif facts.staffing.status == "UNKNOWN":
        readiness = 5
        timing = (
            f"마감까지 {days}일로 최소 준비 기간 {minimum}일을 충족하지만 투입 인력은 미확인입니다."
        )
    else:
        readiness = 15
        timing = (
            f"마감까지 {days}일로 최소 준비 기간 {minimum}일을 충족하고 투입 인력이 확인되었습니다."
        )

    score = (
        {"CONFIRMED": 30, "UNKNOWN": 10, "UNMET": 0}[facts.eligibility.status]
        + {"DIRECT": 25, "PARTNER": 15, "UNKNOWN": 0}[facts.technology.status]
        + {"VERIFIED": 20, "REGISTERED": 10, "NONE": 0}[facts.experience.status]
        + readiness
        + {"CONFIRMED": 10, "UNKNOWN": 5, "UNMET": 0}[facts.prerequisites.status]
    )
    disqualified = (
        expired
        or facts.eligibility.status == "UNMET"
        or facts.prerequisites.status == "UNMET"
        or partner == "UNMET"
    )
    uncertain = facts.eligibility.status == "UNKNOWN" or partner == "UNKNOWN"
    if disqualified:
        score = min(score, 20)
    elif uncertain:
        score = min(score, 45)

    labels = [
        (
            "자격 증빙",
            {"CONFIRMED": "확인", "UNKNOWN": "미확인", "UNMET": "미충족"}[facts.eligibility.status],
            facts.eligibility.evidence,
        ),
        (
            "기술 대응",
            {"DIRECT": "직접 대응", "PARTNER": "확보한 파트너로 보완", "UNKNOWN": "미확인"}[
                facts.technology.status
            ],
            facts.technology.evidence,
        ),
        (
            "완료 실적",
            {"VERIFIED": "인정 확인", "REGISTERED": "등록·증빙 미확인", "NONE": "확인된 실적 없음"}[
                facts.experience.status
            ],
            facts.experience.evidence,
        ),
        (
            "필수 준비 사항",
            {"CONFIRMED": "확인", "UNKNOWN": "미확인", "UNMET": "미충족"}[
                facts.prerequisites.status
            ],
            facts.prerequisites.evidence,
        ),
    ]
    reasons = [f"{name}: {status}({detail.strip()})." for name, status, detail in labels]
    reasons.append(timing)
    reasons.append(f"인력 근거: {facts.staffing.evidence.strip()}")
    if partner == "UNKNOWN":
        reasons.append("필수 파트너의 확보 여부를 확인해야 합니다.")
    if settings.data.criteria.participation_mode == "SOLO" and partner != "NOT_REQUIRED":
        reasons.append("단독 참여 선호와 공동 참여 조건의 조정이 필요합니다.")
    if disqualified:
        reasons.append("신청 불가 또는 필수 조건 미충족으로 점수를 제한했습니다.")
    elif uncertain:
        reasons.append("자격 또는 필수 파트너 미확인으로 점수를 제한했습니다.")
    return OpportunityDimension(
        type=OpportunityDimensionType.FEASIBILITY,
        score=score,
        reason=" ".join(reasons),
    )
