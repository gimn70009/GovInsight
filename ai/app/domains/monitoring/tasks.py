import logging
from uuid import UUID

from app.domains.monitoring.schemas.request import (
    MonitoringJobRequest,
    MonitoringSourceRequest,
)

logger = logging.getLogger(__name__)


def run_monitoring_job(job_id: UUID, request: MonitoringJobRequest) -> None:
    logger.info(
        "모니터링 백그라운드 작업 시작. run_id=%s job_id=%s source_count=%s",
        request.run_id,
        job_id,
        len(request.sources),
    )

    for source in request.sources:
        _process_monitoring_source(request.run_id, job_id, source)

    logger.info(
        "모니터링 백그라운드 작업의 임시 처리를 마침. run_id=%s job_id=%s",
        request.run_id,
        job_id,
    )


def _process_monitoring_source(
    run_id: int,
    job_id: UUID,
    source: MonitoringSourceRequest,
) -> None:
    logger.info(
        "모니터링 소스의 임시 처리를 실행. run_id=%s job_id=%s source_id=%s",
        run_id,
        job_id,
        source.source_id,
    )
