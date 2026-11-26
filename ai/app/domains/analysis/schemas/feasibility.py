"""Evidence judgments for configured scoring; points and dates are code-owned."""

from typing import Literal

from pydantic import BaseModel, Field, model_validator

from app.domains.analysis.schemas.result import OpportunityDimension, OpportunityDimensionType


class EligibilityEvidence(BaseModel):
    status: Literal["CONFIRMED", "UNKNOWN", "UNMET"]
    evidence: str = Field(min_length=5, max_length=45)


class TechnologyEvidence(BaseModel):
    status: Literal["DIRECT", "PARTNER", "UNKNOWN"]
    evidence: str = Field(min_length=5, max_length=45)


class ExperienceEvidence(BaseModel):
    status: Literal["VERIFIED", "REGISTERED", "NONE"]
    evidence: str = Field(min_length=5, max_length=45)


class StaffingEvidence(BaseModel):
    status: Literal["AVAILABLE", "UNKNOWN", "UNAVAILABLE"]
    evidence: str = Field(min_length=5, max_length=45)


class PrerequisiteEvidence(BaseModel):
    status: Literal["CONFIRMED", "UNKNOWN", "UNMET"]
    partner: Literal["NOT_REQUIRED", "CONFIRMED", "UNKNOWN", "UNMET"]
    evidence: str = Field(min_length=5, max_length=45)


class FeasibilityEvidence(BaseModel):
    eligibility: EligibilityEvidence
    technology: TechnologyEvidence
    experience: ExperienceEvidence
    staffing: StaffingEvidence
    prerequisites: PrerequisiteEvidence


class ConfiguredOpportunityDimension(OpportunityDimension):
    type: Literal[
        OpportunityDimensionType.COMPANY_FIT,
        OpportunityDimensionType.BUSINESS_VALUE,
        OpportunityDimensionType.URGENCY,
    ]


class ConfiguredOpportunityAssessment(BaseModel):
    dimensions: list[ConfiguredOpportunityDimension] = Field(min_length=3, max_length=3)
    feasibility: FeasibilityEvidence

    @model_validator(mode="after")
    def validate_dimensions(self) -> "ConfiguredOpportunityAssessment":
        expected = set(OpportunityDimensionType) - {OpportunityDimensionType.FEASIBILITY}
        if {item.type for item in self.dimensions} != expected:
            raise ValueError("실행 가능성 외의 세 평가 항목이 각각 한 번씩 필요합니다.")
        return self
