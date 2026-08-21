import logging
from uuid import UUID

from app.domains.analysis.schemas.request import AnalysisJobRequest

logger = logging.getLogger(__name__)


async def run_analysis_job(job_id: UUID, request: AnalysisJobRequest) -> None:
    logger.info(
        "AI 분석 백그라운드 작업 시작. run_id=%s job_id=%s document_count=%s",
        request.run_id,
        job_id,
        len(request.documents),
    )

    for document in request.documents:
        logger.info(
            "AI 분석 임시 처리. run_id=%s job_id=%s detection_id=%s "
            "version_id=%s change_type=%s attachment_count=%s",
            request.run_id,
            job_id,
            document.detection_id,
            document.version_id,
            document.change_type,
            len(document.attachments),
        )

    logger.info(
        "AI 분석 백그라운드 임시 처리 완료. run_id=%s job_id=%s",
        request.run_id,
        job_id,
    )
