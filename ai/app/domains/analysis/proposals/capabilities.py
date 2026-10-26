"""Company-only evidence for the proposal capability cards."""

from app.domains.analysis.context.company_profile import CompanyProfile
from app.domains.analysis.schemas.result import StrategyCapabilityMatch


def company_capability_catalog(profile: CompanyProfile) -> dict[str, str]:
    # Desired projects, unknown fields and eligibility paperwork are not capabilities.
    groups = (
        ("business", profile.business_areas, "회사의 사업 분야는 {}입니다."),
        ("service", profile.services, "회사는 {} 서비스를 제공합니다."),
        ("technology", profile.technologies, "회사의 기술 분야는 {}입니다."),
        ("case", profile.case_studies, "회사 공개 사례: {}"),
    )
    return {
        f"{kind}:{index}": template.format(value)
        for kind, values, template in groups
        for index, value in enumerate(values, 1)
        if value.strip()
    }


def ground_capability_matches(
    candidates: list[dict], catalog: dict[str, str],
) -> list[dict]:
    """Copy facts from the supplied profile, never from generated notice prose."""
    grounded = []
    seen = set()
    for candidate in candidates:
        evidence_id = candidate.get("company_evidence_id")
        if evidence_id not in catalog or evidence_id in seen:
            continue
        seen.add(evidence_id)
        grounded.append(StrategyCapabilityMatch(
            company_evidence_id=evidence_id,
            confirmed_fact=catalog[evidence_id],
            strategic_interpretation=candidate["strategic_interpretation"],
        ).model_dump())
    return grounded
