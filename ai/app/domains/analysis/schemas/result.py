import re
from enum import StrEnum

from pydantic import BaseModel, Field, field_validator, model_validator

from app.core.schemas import CamelCaseModel


class DocumentImportance(StrEnum):
    HIGH = "HIGH"
    NORMAL = "NORMAL"
    LOW = "LOW"


class Eligibility(StrEnum):
    ELIGIBLE = "ELIGIBLE"
    INELIGIBLE = "INELIGIBLE"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"


class Favorability(StrEnum):
    FAVORABLE = "FAVORABLE"
    UNFAVORABLE = "UNFAVORABLE"
    NEUTRAL = "NEUTRAL"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"


class ProposalDocumentType(StrEnum):
    GENERAL_NOTICE = "GENERAL_NOTICE"
    BUSINESS_NOTICE = "BUSINESS_NOTICE"
    PROPOSAL_REQUEST = "PROPOSAL_REQUEST"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"


class ProposalDraftStatus(StrEnum):
    NOT_APPLICABLE = "NOT_APPLICABLE"
    READY = "READY"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    NOT_RECOMMENDED = "NOT_RECOMMENDED"
    GENERATING = "GENERATING"


class PreparationStatus(StrEnum):
    READY = "READY"
    VERIFIED = "VERIFIED"
    LIKELY = "LIKELY"
    ACTION_REQUIRED = "ACTION_REQUIRED"
    NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION"
    MISSING = "MISSING"
    INELIGIBLE = "INELIGIBLE"
    NOT_APPLICABLE = "NOT_APPLICABLE"


class RequirementLevel(StrEnum):
    MANDATORY = "MANDATORY"
    CONDITIONAL = "CONDITIONAL"
    OPTIONAL = "OPTIONAL"
    RECOMMENDED = "RECOMMENDED"


class RequirementStage(StrEnum):
    APPLICATION = "APPLICATION"
    EVALUATION = "EVALUATION"
    POST_SELECTION = "POST_SELECTION"
    AGREEMENT = "AGREEMENT"
    EXECUTION = "EXECUTION"
    REPORTING = "REPORTING"


class EvidenceOrigin(StrEnum):
    NOTICE_BODY = "NOTICE_BODY"
    ATTACHMENT = "ATTACHMENT"
    COMPANY_PROFILE = "COMPANY_PROFILE"
    COMPANY_INPUT = "COMPANY_INPUT"
    AI_RECOMMENDATION = "AI_RECOMMENDATION"


class CompanyEvidenceLevel(StrEnum):
    OFFICIAL_DOCUMENT = "OFFICIAL_DOCUMENT"
    USER_CONFIRMED = "USER_CONFIRMED"
    OFFICIAL_WEBSITE = "OFFICIAL_WEBSITE"
    PUBLIC_INFORMATION = "PUBLIC_INFORMATION"
    UNKNOWN = "UNKNOWN"


class StrategyDecision(StrEnum):
    GO = "GO"
    CONDITIONAL_GO = "CONDITIONAL_GO"
    HOLD = "HOLD"
    NO_GO = "NO_GO"


class StopCriterionType(StrEnum):
    OFFICIAL_REQUIREMENT = "OFFICIAL_REQUIREMENT"
    INTERNAL_RECOMMENDATION = "INTERNAL_RECOMMENDATION"


class PreparationWorkType(StrEnum):
    INTERNAL_CONFIRMATION = "INTERNAL_CONFIRMATION"
    EXTERNAL_CONFIRMATION = "EXTERNAL_CONFIRMATION"
    DOCUMENT_ISSUANCE = "DOCUMENT_ISSUANCE"
    CERTIFICATION = "CERTIFICATION"
    SIGNATURE_SEAL = "SIGNATURE_SEAL"
    BUDGET_REVIEW = "BUDGET_REVIEW"
    PROPOSAL_WRITING = "PROPOSAL_WRITING"
    TECHNICAL_PLANNING = "TECHNICAL_PLANNING"
    DOMESTIC_PARTNER = "DOMESTIC_PARTNER"
    INTERNATIONAL_PARTNER = "INTERNATIONAL_PARTNER"
    LEGAL_CONTRACT = "LEGAL_CONTRACT"
    OTHER = "OTHER"


def _normalize_strategy_sentence(value: str) -> str:
    normalized = re.sub(r"\s*[;；]\s*", ", ", value.strip())
    normalized = re.sub(r"(?i)(?<![a-z])GO로", "지원 권장으로", normalized)
    normalized = re.sub(r"(?i)(?<![a-z])GO를", "지원 권장을", normalized)
    normalized = re.sub(r"(?i)(?<![a-z])NO[- ]?GO(?![a-z])", "지원 비권장", normalized)
    normalized = re.sub(r"(?i)(?<![a-z])GO(?![a-z])", "지원 권장", normalized)
    normalized = re.sub(r"(?i)MoU\s*/\s*LOI", "협력의향서 또는 참여확인서", normalized)
    normalized = re.sub(r"(?i)LOI\s*/\s*MoU", "참여확인서 또는 협력의향서", normalized)
    polite_endings = {
        "해야 한다": "해야 합니다",
        "할 수 없다": "할 수 없습니다",
        "할 수 있다": "할 수 있습니다",
        "필요하다": "필요합니다",
        "확인한다": "확인합니다",
        "확보한다": "확보합니다",
        "검토한다": "검토합니다",
        "수행한다": "수행합니다",
        "참여한다": "참여합니다",
        "담당한다": "담당합니다",
        "중단한다": "중단합니다",
        "권장한다": "권장합니다",
        "판단한다": "판단합니다",
        "된다": "됩니다",
        "없다": "없습니다",
        "있다": "있습니다",
        "이다": "입니다",
    }
    for source, target in polite_endings.items():
        normalized = re.sub(
            rf"{re.escape(source)}(?=[,.!?]|$)",
            target,
            normalized,
        )
    return normalized


def _normalize_user_sentence(value: str) -> str:
    normalized = _normalize_strategy_sentence(value)
    return re.sub(r"\s+·\s+", ", ", normalized)


def _strip_form_number(value: str) -> str:
    normalized = re.sub(r"^\s*[（(]\s*(?:양식|서식)\s*\d+\s*[)）]\s*", "", value.strip())
    return normalized or value.strip()


class OpportunityDimensionType(StrEnum):
    COMPANY_FIT = "COMPANY_FIT"
    BUSINESS_VALUE = "BUSINESS_VALUE"
    FEASIBILITY = "FEASIBILITY"
    URGENCY = "URGENCY"
    EVIDENCE_CONFIDENCE = "EVIDENCE_CONFIDENCE"


class OpportunityDimension(BaseModel):
    type: OpportunityDimensionType
    score: int = Field(ge=0, le=100)
    reason: str = Field(min_length=10, max_length=500)

    @field_validator("reason")
    @classmethod
    def strip_reason(cls, value: str) -> str:
        return value.strip()


class OpportunityAssessment(BaseModel):
    dimensions: list[OpportunityDimension] = Field(min_length=5, max_length=5)

    @model_validator(mode="after")
    def validate_dimensions(self) -> "OpportunityAssessment":
        expected = set(OpportunityDimensionType)
        actual = {dimension.type for dimension in self.dimensions}
        if actual != expected:
            raise ValueError("기회 점수의 다섯 평가 항목이 각각 한 번씩 필요합니다.")
        return self


class ProposalSection(BaseModel):
    title: str = Field(min_length=2, max_length=100)
    body: str = Field(min_length=10, max_length=1000)

    @field_validator("title", "body")
    @classmethod
    def strip_section_text(cls, value: str) -> str:
        return value.strip()


class RequirementSource(CamelCaseModel):
    origin: EvidenceOrigin
    attachment_name: str | None = Field(default=None, max_length=500)
    section_title: str = Field(min_length=2, max_length=200)
    location: str | None = Field(default=None, max_length=200)
    excerpt: str = Field(min_length=5, max_length=300)


class PreparationChecklistItem(CamelCaseModel):
    title: str = Field(min_length=2, max_length=150)
    status: PreparationStatus
    detail: str = Field(min_length=10, max_length=500)
    next_action: str = Field(min_length=5, max_length=300)
    requirement_level: RequirementLevel = RequirementLevel.RECOMMENDED
    stage: RequirementStage = RequirementStage.APPLICATION
    applies_to: str = Field(default="신청기관", min_length=2, max_length=200)
    source: RequirementSource | None = None
    company_evidence_level: CompanyEvidenceLevel = CompanyEvidenceLevel.UNKNOWN
    readiness_score: int = Field(default=0, ge=0, le=100)
    condition_score: int | None = Field(default=None, ge=0, le=100)
    evidence_score: int = Field(default=0, ge=0, le=100)
    schedule_score: int = Field(default=0, ge=0, le=100)
    work_type: PreparationWorkType = PreparationWorkType.OTHER
    estimated_business_days: int = Field(default=3, ge=1, le=120)
    score_basis: list[str] = Field(default_factory=list, max_length=4)

    @field_validator("title")
    @classmethod
    def normalize_title(cls, value: str) -> str:
        return _strip_form_number(value)

    @field_validator("detail", "next_action", "applies_to")
    @classmethod
    def normalize_user_text(cls, value: str) -> str:
        return _normalize_user_sentence(value)

    @model_validator(mode="after")
    def validate_evidence(self) -> "PreparationChecklistItem":
        if (
            self.requirement_level == RequirementLevel.CONDITIONAL
            and self.applies_to == "신청기관"
        ):
            self.applies_to = "적용 조건 확인 필요"
        if self.status == PreparationStatus.VERIFIED and self.company_evidence_level not in {
            CompanyEvidenceLevel.OFFICIAL_DOCUMENT,
            CompanyEvidenceLevel.USER_CONFIRMED,
        }:
            self.status = PreparationStatus.LIKELY
        return self


class StrategyCapabilityMatch(CamelCaseModel):
    confirmed_fact: str = Field(min_length=10, max_length=500)
    strategic_interpretation: str = Field(min_length=10, max_length=500)

    @field_validator("confirmed_fact", "strategic_interpretation")
    @classmethod
    def normalize_sentences(cls, value: str) -> str:
        return _normalize_strategy_sentence(value)


class StrategyGap(CamelCaseModel):
    gap: str = Field(min_length=5, max_length=300)
    next_action: str = Field(min_length=5, max_length=300)
    owner: str = Field(min_length=2, max_length=100)
    target_timing: str = Field(min_length=3, max_length=150)
    work_type: PreparationWorkType = PreparationWorkType.OTHER
    estimated_business_days: int = Field(default=5, ge=1, le=120)
    target_date: str | None = Field(default=None, max_length=10)
    schedule_basis: str | None = Field(default=None, max_length=300)

    @field_validator("gap", "next_action", "target_timing")
    @classmethod
    def normalize_sentences(cls, value: str) -> str:
        return _normalize_strategy_sentence(value)


class StrategyStopCriterion(CamelCaseModel):
    type: StopCriterionType
    condition: str = Field(min_length=10, max_length=400)
    rationale: str = Field(min_length=10, max_length=400)

    @field_validator("condition", "rationale")
    @classmethod
    def normalize_sentences(cls, value: str) -> str:
        return _normalize_strategy_sentence(value)


class StrategyOnePage(CamelCaseModel):
    decision: StrategyDecision
    decision_reason: str = Field(min_length=10, max_length=500)
    recommended_project: str = Field(min_length=5, max_length=120)
    recommended_participation: str = Field(min_length=10, max_length=500)
    alternative_participation: str = Field(min_length=10, max_length=500)
    capability_matches: list[StrategyCapabilityMatch] = Field(min_length=1, max_length=4)
    critical_gaps: list[StrategyGap] = Field(min_length=1, max_length=4)
    stop_criteria: list[StrategyStopCriterion] = Field(min_length=1, max_length=4)

    @field_validator(
        "decision_reason",
        "recommended_project",
        "recommended_participation",
        "alternative_participation",
    )
    @classmethod
    def normalize_sentences(cls, value: str) -> str:
        return _normalize_strategy_sentence(value)


class ProposalPreparation(CamelCaseModel):
    meeting_agenda: list[str] = Field(min_length=3, max_length=8)
    eligibility_checklist: list[PreparationChecklistItem] = Field(min_length=1, max_length=12)
    submission_documents: list[PreparationChecklistItem] = Field(min_length=1, max_length=15)
    company_inputs: list[PreparationChecklistItem] = Field(min_length=1, max_length=12)
    application_deadline: str | None = Field(default=None, max_length=10)
    strategy: StrategyOnePage

    @field_validator("meeting_agenda")
    @classmethod
    def normalize_agenda(cls, values: list[str]) -> list[str]:
        return [_normalize_user_sentence(value) for value in values]

class ProposalStrategy(CamelCaseModel):
    sections: list[ProposalSection] = Field(min_length=1, max_length=6)
    document_type: ProposalDocumentType = ProposalDocumentType.REVIEW_REQUIRED
    draft_status: ProposalDraftStatus = ProposalDraftStatus.NOT_APPLICABLE
    draft_reason: str = Field(
        default="기존 분석 결과에는 제안서 판정 정보가 없습니다.", min_length=10, max_length=1000
    )
    source_attachment_names: list[str] = Field(default_factory=list, max_length=10)
    template_sections: list[str] = Field(default_factory=list, max_length=30)
    draft_sections: list[ProposalSection] = Field(default_factory=list, max_length=8)
    preparation: ProposalPreparation | None = None
    preparation_schema_version: int = Field(default=1, ge=1, le=10)

    @field_validator("draft_reason")
    @classmethod
    def strip_draft_reason(cls, value: str) -> str:
        return value.strip()

    @field_validator("source_attachment_names", "template_sections")
    @classmethod
    def normalize_string_lists(cls, values: list[str]) -> list[str]:
        return list(dict.fromkeys(value.strip() for value in values if value.strip()))


class AnalysisDraft(BaseModel):
    summary: str = Field(min_length=20, max_length=4000)
    key_points: list[str] = Field(min_length=1, max_length=8)
    importance: DocumentImportance
    reason: str = Field(min_length=10, max_length=1000)
    eligibility: Eligibility
    favorable_or_not: Favorability
    proposal: ProposalStrategy
    opportunity: OpportunityAssessment

    @field_validator("summary", "reason")
    @classmethod
    def strip_text(cls, value: str) -> str:
        return value.strip()

    @field_validator("key_points")
    @classmethod
    def normalize_key_points(cls, values: list[str]) -> list[str]:
        normalized = [value.strip() for value in values if value.strip()]
        if not normalized:
            raise ValueError("핵심 내용이 한 개 이상 필요합니다.")
        return normalized

    @model_validator(mode="after")
    def validate_proposal_draft_policy(self) -> "AnalysisDraft":
        proposal = self.proposal
        if proposal.document_type in {
            ProposalDocumentType.GENERAL_NOTICE,
            ProposalDocumentType.BUSINESS_NOTICE,
        }:
            if proposal.draft_status != ProposalDraftStatus.NOT_APPLICABLE:
                raise ValueError(
                    "일반 공지와 제출 양식 없는 사업 공고는 제안서 초안 대상이 아닙니다."
                )
            if (
                proposal.source_attachment_names
                or proposal.template_sections
                or proposal.draft_sections
                or proposal.preparation is not None
            ):
                raise ValueError("제안서 대상이 아닌 문서에는 양식 또는 초안을 포함할 수 없습니다.")

        if proposal.document_type != ProposalDocumentType.PROPOSAL_REQUEST:
            return self

        company_fit = next(
            dimension.score
            for dimension in self.opportunity.dimensions
            if dimension.type == OpportunityDimensionType.COMPANY_FIT
        )
        if company_fit <= 40 or self.eligibility == Eligibility.INELIGIBLE:
            if proposal.draft_status != ProposalDraftStatus.NOT_RECOMMENDED:
                raise ValueError(
                    "회사 부적합 또는 신청 불가 사업은 제안서 작성을 권장할 수 없습니다."
                )
            if proposal.draft_sections or proposal.preparation is not None:
                raise ValueError("제안 비권장 사업에는 제안 준비안을 생성할 수 없습니다.")
        elif company_fit >= 61 and proposal.template_sections:
            has_preparation = proposal.preparation is not None
            has_legacy_draft = bool(proposal.draft_sections)
            if proposal.draft_status != ProposalDraftStatus.READY or not (
                has_preparation or has_legacy_draft
            ):
                raise ValueError("회사 적합 사업의 양식이 확인되면 제안 준비안이 필요합니다.")
            draft_titles = [section.title for section in proposal.draft_sections]
            template_positions = {
                title: index for index, title in enumerate(proposal.template_sections)
            }
            if any(title not in template_positions for title in draft_titles):
                raise ValueError("제안서 초안 제목은 확정된 전체 목차에 포함되어야 합니다.")
            draft_positions = [template_positions[title] for title in draft_titles]
            if draft_positions != sorted(draft_positions):
                raise ValueError("핵심 제안서 초안은 전체 목차의 순서를 유지해야 합니다.")
            if any("[회사 확인 필요" in section.body for section in proposal.draft_sections):
                raise ValueError(
                    "미확정 정보는 괄호형 표식이 아니라 자연스러운 "
                    "확인 안내 문장으로 작성해야 합니다."
                )
        elif proposal.draft_status not in {
            ProposalDraftStatus.REVIEW_REQUIRED,
            ProposalDraftStatus.GENERATING,
        }:
            raise ValueError("제안서 양식 또는 회사 적합성이 불명확하면 추가 검토가 필요합니다.")
        return self


class DocumentAnalysisResult(CamelCaseModel):
    detection_id: int = Field(gt=0)
    document_id: int = Field(gt=0)
    version_id: int = Field(gt=0)
    summary: str
    key_points: list[str]
    importance: DocumentImportance
    reason: str
    eligibility: Eligibility
    favorable_or_not: Favorability
    proposal: ProposalStrategy
    opportunity: OpportunityAssessment
    used_tools: list[str]
    model_name: str
