from enum import StrEnum

from pydantic import BaseModel, Field, field_validator

from app.core.schemas import CamelCaseModel


class DocumentImportance(StrEnum):
    HIGH = "HIGH"
    NORMAL = "NORMAL"
    LOW = "LOW"


class AnalysisDraft(BaseModel):
    summary: str = Field(min_length=20, max_length=4000)
    key_points: list[str] = Field(min_length=1, max_length=8)
    importance: DocumentImportance
    reason: str = Field(min_length=10, max_length=1000)

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
    used_tools: list[str]
    model_name: str
