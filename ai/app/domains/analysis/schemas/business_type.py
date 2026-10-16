"""The summary screen has one representative activity, backed by current notice quotes."""

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field

from app.core.schemas import CamelCaseModel


class BusinessTypeCode(StrEnum):
    RND = "RND"
    DEMONSTRATION = "DEMONSTRATION"
    COMMERCIALIZATION = "COMMERCIALIZATION"
    ADOPTION = "ADOPTION"
    CERTIFICATION = "CERTIFICATION"
    MARKET = "MARKET"
    FINANCE = "FINANCE"
    WORKFORCE = "WORKFORCE"
    ADVISORY = "ADVISORY"
    SPACE = "SPACE"


class BusinessTypeEvidence(CamelCaseModel):
    origin: Literal["NOTICE_BODY", "ATTACHMENT"]
    attachment_name: str | None = Field(default=None, max_length=500)
    excerpt: str = Field(min_length=5, max_length=1000)


class BusinessTypeAssessment(CamelCaseModel):
    primary_type: BusinessTypeCode
    explanation: str = Field(min_length=1, max_length=500)
    details: list[Annotated[str, Field(min_length=1, max_length=300)]] = Field(max_length=5)
    sources: list[BusinessTypeEvidence] = Field(min_length=1, max_length=4)


class BusinessTypeReview(CamelCaseModel):
    validator_version: str = Field(min_length=1, max_length=80)
    candidate_type: BusinessTypeCode | None = None
    reason: str = Field(min_length=1, max_length=40, pattern=r"^[A-Z_]+$")
    source_index: int | None = Field(default=None, ge=0, le=3)
    notice_kind: Literal[
        "RESULT", "REGULATION", "AWARD", "DESIGNATION_RECRUITMENT", "GENERAL"
    ] | None = None
