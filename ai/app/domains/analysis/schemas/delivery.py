from uuid import UUID

from pydantic import Field, model_validator

from app.core.schemas import CamelCaseModel
from app.domains.analysis.schemas.result import DocumentAnalysisResult, ProposalStrategy


class AnalysisFailureResult(CamelCaseModel):
    detection_id: int = Field(gt=0)
    document_id: int = Field(gt=0)
    version_id: int = Field(gt=0)
    error_message: str = Field(min_length=1, max_length=2000)


class AnalysisResultRequest(CamelCaseModel):
    run_id: int = Field(gt=0)
    job_id: UUID
    results: list[DocumentAnalysisResult]
    failures: list[AnalysisFailureResult]

    @model_validator(mode="after")
    def require_result(self) -> "AnalysisResultRequest":
        if not self.results and not self.failures:
            raise ValueError("분석 성공 또는 실패 결과가 한 건 이상 필요합니다.")
        return self


class AnalysisResultData(CamelCaseModel):
    run_id: int
    stored_analysis_count: int
    duplicate_analysis_count: int
    failed_analysis_count: int


class AnalysisResultResponse(CamelCaseModel):
    is_success: bool
    code: str
    http_status: int
    message: str
    data: AnalysisResultData


class ProposalUpdateResult(CamelCaseModel):
    detection_id: int = Field(gt=0)
    document_id: int = Field(gt=0)
    version_id: int = Field(gt=0)
    proposal: ProposalStrategy
    used_tools: list[str] = Field(max_length=20)


class ProposalResultRequest(CamelCaseModel):
    run_id: int = Field(gt=0)
    job_id: UUID
    results: list[ProposalUpdateResult]


class ProposalResultData(CamelCaseModel):
    run_id: int
    updated_proposal_count: int


class ProposalResultResponse(CamelCaseModel):
    is_success: bool
    code: str
    http_status: int
    message: str
    data: ProposalResultData
