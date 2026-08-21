import logging
from uuid import UUID

from app.domains.analysis.agent import LangChainAnalysisRunner
from app.domains.analysis.config import AnalysisConfigurationError, AnalysisSettings
from app.domains.analysis.graph import AnalysisWorkflowError, DocumentAnalysisWorkflow
from app.domains.analysis.schemas.request import AnalysisJobRequest

logger = logging.getLogger(__name__)


async def run_analysis_job(job_id: UUID, request: AnalysisJobRequest) -> None:
    logger.info(
        "AI 분석 백그라운드 작업 시작. run_id=%s job_id=%s document_count=%s",
        request.run_id,
        job_id,
        len(request.documents),
    )

    try:
        settings = AnalysisSettings.from_env()
        workflow = DocumentAnalysisWorkflow(
            runner=LangChainAnalysisRunner(settings),
            max_attempts=settings.max_attempts,
        )
    except AnalysisConfigurationError as exception:
        logger.error(
            "AI 분석 설정 오류. run_id=%s job_id=%s error=%s",
            request.run_id,
            job_id,
            exception,
        )
        return

    success_count = 0
    failed_count = 0
    for document in request.documents:
        try:
            result = await workflow.analyze(document)
            success_count += 1
            logger.info(
                "AI 문서 분석 완료. run_id=%s job_id=%s detection_id=%s "
                "version_id=%s importance=%s used_tools=%s",
                request.run_id,
                job_id,
                result.detection_id,
                result.version_id,
                result.importance,
                ",".join(result.used_tools),
            )
        except AnalysisWorkflowError as exception:
            failed_count += 1
            logger.error(
                "AI 문서 분석 실패. run_id=%s job_id=%s detection_id=%s "
                "version_id=%s error=%s",
                request.run_id,
                job_id,
                document.detection_id,
                document.version_id,
                exception,
            )

    logger.info(
        "AI 분석 백그라운드 작업 완료. run_id=%s job_id=%s "
        "success_count=%s failed_count=%s",
        request.run_id,
        job_id,
        success_count,
        failed_count,
    )
