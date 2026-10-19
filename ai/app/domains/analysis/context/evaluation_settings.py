from datetime import date
from typing import Literal

from pydantic import Field

from app.core.schemas import CamelCaseModel
from app.domains.analysis.context.company_profile import BISTELLIGENCE_PROFILE, CompanyProfile


class RegisteredProject(CamelCaseModel):
    title: str = Field(min_length=1, max_length=150)
    description: str = Field(max_length=1000)
    status: Literal["COMPLETED", "IN_PROGRESS", "PLANNED"]


class RegisteredCertification(CamelCaseModel):
    name: str = Field(min_length=1, max_length=150)
    expires_on: date | None = None
    status: Literal["HELD", "NEEDS_VERIFICATION", "EXPIRED"]


class RegisteredCompany(CamelCaseModel):
    name: str = Field(min_length=1, max_length=100)
    type: Literal[
        "UNKNOWN", "SME", "MID_SIZED", "LARGE", "SOLE_PROPRIETOR", "RESEARCH", "NONPROFIT"
    ]
    location: str = Field(max_length=200)
    established_on: date | None = None
    description: str = Field(max_length=2000)
    industries: list[str] = Field(min_length=1, max_length=16)
    technologies: list[str] = Field(min_length=1, max_length=46)
    projects: list[RegisteredProject] = Field(max_length=20)
    certifications: list[RegisteredCertification] = Field(max_length=20)


class EvaluationCriteria(CamelCaseModel):
    industries: list[str] = Field(min_length=1, max_length=16)
    tasks: list[str] = Field(min_length=1, max_length=46)
    roles: list[str] = Field(min_length=1, max_length=6)
    business_types: list[str] = Field(min_length=1, max_length=12)
    goals: list[str] = Field(min_length=1, max_length=3)
    preparation_days: Literal[7, 14, 21, 30] | None = None
    participation_mode: Literal["ANY", "SOLO", "CONSORTIUM"]
    urgency_days: Literal[3, 7, 14, 30]
    preset: Literal["FIT", "GROWTH", "FEASIBILITY"]


class EvaluationSettingsData(CamelCaseModel):
    company: RegisteredCompany
    criteria: EvaluationCriteria


class EvaluationSettingsSnapshot(CamelCaseModel):
    revision: int = Field(gt=0)
    data: EvaluationSettingsData


def profile_for_settings(settings: EvaluationSettingsSnapshot | None) -> CompanyProfile:
    if settings is None:
        return BISTELLIGENCE_PROFILE
    company = settings.data.company
    types = {
        "SME": "중소기업",
        "MID_SIZED": "중견기업",
        "LARGE": "대기업",
        "SOLE_PROPRIETOR": "개인사업자",
        "RESEARCH": "대학·연구기관",
        "NONPROFIT": "비영리기관",
    }
    facts = []
    unknown = [
        "공고별 자격의 공식 증빙과 수행 실적 인정 여부",
        "현재 투입 가능한 인력·예산 및 필수 파트너 확보 여부",
    ]
    if company.type in types:
        facts.append(f"사용자 등록 기업 유형: {types[company.type]} (공식 증빙 별도 확인)")
    else:
        unknown.append("기업 유형")
    if company.established_on:
        facts.append(f"사용자 등록 설립일: {company.established_on.isoformat()}")
    else:
        unknown.append("설립일 및 업력")
    if not company.location.strip():
        unknown.append("소재지")
    status_labels = {
        "HELD": "사용자 등록 보유 정보·증빙 별도 확인",
        "NEEDS_VERIFICATION": "확인 필요",
        "EXPIRED": "만료",
    }
    for cert in company.certifications:
        expiry = cert.expires_on.isoformat() if cert.expires_on else "유효기간 미등록"
        facts.append(f"인증·자격 {cert.name}: {status_labels[cert.status]}, {expiry}")
    if not company.certifications:
        unknown.append("인증·자격 보유 여부 (미등록이며 미보유로 확정하지 않음)")
    cases = []
    for project in company.projects:
        text = f"{project.title}: {project.description}".rstrip(": ")
        if project.status == "COMPLETED":
            cases.append(f"사용자 등록 완료 실적 (증빙·인정 여부 별도 확인): {text}")
        else:
            status = "진행 중" if project.status == "IN_PROGRESS" else "계획 단계"
            facts.append(f"{status} 과제 (완료 실적 아님): {text}")
    if not cases:
        unknown.append("완료 수행 실적 및 증빙")
    return CompanyProfile(
        company_name=company.name,
        description=company.description,
        verified_facts=tuple(facts),
        business_areas=tuple(company.industries),
        services=tuple(company.technologies),
        technologies=tuple(company.technologies),
        target_industries=tuple(company.industries),
        relevant_project_types=tuple(company.technologies),
        case_studies=tuple(cases),
        location=company.location,
        evidence_limitations=(
            "회사 정보는 사용자 등록값이며 공식 증빙을 자동 검증하지 않았음",
            "관심 산업·과업과 참여 의향은 보유 역량·완료 실적·확보된 파트너가 아님",
        ),
        unknown_fields=tuple(unknown),
    )
