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


class ProposalStrategy(BaseModel):
    sections: list[ProposalSection] = Field(min_length=1, max_length=6)


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
