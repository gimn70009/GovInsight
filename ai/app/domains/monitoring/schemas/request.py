from pydantic import Field

from app.core.schemas import CamelCaseModel


# 모니터링 작업에 포함되는 개별 소스의 요청 정보를 검증하는 DTO
class MonitoringSourceRequest(CamelCaseModel):
    source_id: int = Field(gt=0)
    organization_name: str = Field(min_length=1, max_length=100)
    board_name: str = Field(min_length=1, max_length=100)
    list_url: str = Field(min_length=1, max_length=1000)
    url_include_pattern: str | None = Field(default=None, max_length=500)
    detail_fetch_count: int = Field(gt=0)


# 실행 ID와 여러 모니터링 소스를 담는 전체 작업 요청 DTO
class MonitoringJobRequest(CamelCaseModel):
    run_id: int = Field(gt=0)
    sources: list[MonitoringSourceRequest] = Field(min_length=1)
