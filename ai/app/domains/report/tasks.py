import logging
from uuid import UUID

from app.domains.report.clients import ReportResultClient, ReportResultClientError
from app.domains.report.schemas.delivery import ReportResultRequest, ReportResultStatus
from app.domains.report.schemas.request import ReportJobRequest
from app.domains.report.template import TemplateReportGenerator

logger = logging.getLogger(__name__)


async def run_report_job(job_id: UUID, request: ReportJobRequest) -> None:
    logger.info(
        "모니터링 보고서 백그라운드 작업 시작. run_id=%s job_id=%s document_count=%s",
        request.run_id,
        job_id,
        len(request.documents),
    )

    try:
        draft = TemplateReportGenerator().generate(request)
        result = ReportResultRequest(
            run_id=request.run_id,
            job_id=job_id,
            status=ReportResultStatus.COMPLETED,
            title=draft.title,
            summary=draft.summary,
        )
    except Exception as exception:
        logger.error(
            "모니터링 보고서 생성 실패. run_id=%s job_id=%s error=%s",
            request.run_id,
            job_id,
            exception,
        )
        result = ReportResultRequest(
            run_id=request.run_id,
            job_id=job_id,
            status=ReportResultStatus.FAILED,
            error_message=str(exception)[:2000] or "보고서 생성에 실패했습니다.",
        )

    try:
        response = await ReportResultClient().send(result)
        logger.info(
            "모니터링 보고서 결과 전달 완료. run_id=%s job_id=%s "
            "report_id=%s status=%s duplicate=%s",
            request.run_id,
            job_id,
            response.data.report_id,
            response.data.status,
            response.data.duplicate,
        )
    except ReportResultClientError as exception:
        logger.error(
            "모니터링 보고서 결과 전달 실패. run_id=%s job_id=%s error=%s",
            request.run_id,
            job_id,
            exception,
        )
