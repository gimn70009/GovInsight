from enum import StrEnum
from uuid import UUID

from pydantic import Field, model_validator

from app.core.schemas import CamelCaseModel


class ReportResultStatus(StrEnum):
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class ReportResultRequest(CamelCaseModel):
    run_id: int = Field(gt=0)
    job_id: UUID
    status: ReportResultStatus
    title: str | None = Field(default=None, max_length=500)
    summary: str | None = Field(default=None, max_length=20_000)
    error_message: str | None = Field(default=None, max_length=2000)

    @model_validator(mode="after")
    def validate_result(self) -> "ReportResultRequest":
        if self.status == ReportResultStatus.COMPLETED:
            if not self.title or not self.summary:
                raise ValueError("완료 결과에는 제목과 요약이 필요합니다.")
            if self.error_message:
                raise ValueError("완료 결과에는 오류 메시지를 포함할 수 없습니다.")
        elif not self.error_message:
            raise ValueError("실패 결과에는 오류 메시지가 필요합니다.")
        return self


class ReportResultData(CamelCaseModel):
    run_id: int
    report_id: int
    status: str
    duplicate: bool


class ReportResultResponse(CamelCaseModel):
    is_success: bool
    code: str
    http_status: int
    message: str
    data: ReportResultData
