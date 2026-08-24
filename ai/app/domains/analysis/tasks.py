import asyncio
import logging
from typing import Protocol
from uuid import UUID

from app.domains.analysis.agent import LangChainAnalysisRunner
from app.domains.analysis.clients import AnalysisResultClient, AnalysisResultClientError
from app.domains.analysis.config import AnalysisConfigurationError, AnalysisSettings
from app.domains.analysis.graph import AnalysisWorkflowError, DocumentAnalysisWorkflow
from app.domains.analysis.schemas.delivery import (
    AnalysisFailureResult,
    AnalysisResultRequest,
)
from app.domains.analysis.schemas.request import (
    AnalysisDocumentRequest,
    AnalysisJobRequest,
)
from app.domains.analysis.schemas.result import DocumentAnalysisResult

logger = logging.getLogger(__name__)


class AnalysisWorkflow(Protocol):
    async def analyze(
        self,
        document: AnalysisDocumentRequest,
    ) -> DocumentAnalysisResult: ...


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

    results, failures = await _analyze_documents(
        workflow,
        request.documents,
        settings.concurrency,
    )
    for result in results:
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
    for failure in failures:
        logger.error(
            "AI 문서 분석 실패. run_id=%s job_id=%s detection_id=%s version_id=%s error=%s",
            request.run_id,
            job_id,
            failure.detection_id,
            failure.version_id,
            failure.error_message,
        )

    delivery_request = AnalysisResultRequest(
        run_id=request.run_id,
        job_id=job_id,
        results=results,
        failures=failures,
    )
    try:
        response = await AnalysisResultClient(
            max_attempts=settings.result_delivery_max_attempts
        ).send(delivery_request)
        logger.info(
            "AI 분석 결과 전달 완료. run_id=%s job_id=%s "
            "stored_count=%s duplicate_count=%s failed_count=%s",
            request.run_id,
            job_id,
            response.data.stored_analysis_count,
            response.data.duplicate_analysis_count,
            response.data.failed_analysis_count,
        )
    except AnalysisResultClientError as exception:
        logger.error(
            "AI 분석 결과 전달 실패. run_id=%s job_id=%s success_count=%s failed_count=%s error=%s",
            request.run_id,
            job_id,
            len(results),
            len(failures),
            exception,
        )


async def _analyze_documents(
    workflow: AnalysisWorkflow,
    documents: list[AnalysisDocumentRequest],
    concurrency: int,
) -> tuple[list[DocumentAnalysisResult], list[AnalysisFailureResult]]:
    semaphore = asyncio.Semaphore(concurrency)

    async def analyze_one(
        document: AnalysisDocumentRequest,
    ) -> tuple[DocumentAnalysisResult | None, AnalysisFailureResult | None]:
        async with semaphore:
            try:
                return await workflow.analyze(document), None
            except AnalysisWorkflowError as exception:
                return None, AnalysisFailureResult(
                    detection_id=document.detection_id,
                    document_id=document.document_id,
                    version_id=document.version_id,
                    error_message=str(exception),
                )

    outcomes = await asyncio.gather(*(analyze_one(document) for document in documents))
    results = [result for result, _failure in outcomes if result is not None]
    failures = [failure for _result, failure in outcomes if failure is not None]
    return results, failures
