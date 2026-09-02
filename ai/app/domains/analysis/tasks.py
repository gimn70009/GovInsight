import asyncio
import logging
from typing import Protocol
from uuid import UUID

from app.domains.analysis.agent import LangChainAnalysisRunner
from app.domains.analysis.clients import AnalysisResultClient, AnalysisResultClientError
from app.domains.analysis.config import AnalysisConfigurationError, AnalysisSettings
from app.domains.analysis.graph import AnalysisWorkflowError, DocumentAnalysisWorkflow
from app.domains.analysis.proposal_drafting import (
    LangChainProposalGenerationRunner,
    TwoStageAnalysisWorkflow,
    _requires_proposal_generation,
    apply_proposal_generation_reason,
)
from app.domains.analysis.schemas.delivery import (
    AnalysisFailureResult,
    AnalysisResultRequest,
    ProposalResultRequest,
    ProposalUpdateResult,
)
from app.domains.analysis.schemas.request import (
    AnalysisDocumentRequest,
    AnalysisJobRequest,
)
from app.domains.analysis.schemas.result import (
    DocumentAnalysisResult,
    ProposalDraftStatus,
)

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
        base_workflow = DocumentAnalysisWorkflow(
            runner=LangChainAnalysisRunner(settings),
            max_attempts=settings.max_attempts,
        )
        proposal_runner = LangChainProposalGenerationRunner(settings)
    except AnalysisConfigurationError as exception:
        logger.error(
            "AI 분석 설정 오류. run_id=%s job_id=%s error=%s",
            request.run_id,
            job_id,
            exception,
        )
        return

    results, failures = await _analyze_documents(
        base_workflow,
        request.documents,
        settings.concurrency,
    )
    documents_by_version = {document.version_id: document for document in request.documents}
    for result in results:
        document = documents_by_version.get(result.version_id)
        if document is not None:
            apply_proposal_generation_reason(result, document)
    _mark_generating_proposals(results, request.documents)
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
        result_client = AnalysisResultClient(
            max_attempts=settings.result_delivery_max_attempts
        )
        response = await result_client.send(delivery_request)
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
        return

    try:
        await _generate_and_deliver_proposals(
            job_id,
            request,
            results,
            proposal_runner,
            result_client,
            settings.concurrency,
        )
    except AnalysisResultClientError as exception:
        logger.error(
            "사업 제안 결과 전달 실패. run_id=%s job_id=%s error=%s",
            request.run_id,
            job_id,
            exception,
        )


def _mark_generating_proposals(
    results: list[DocumentAnalysisResult],
    documents: list[AnalysisDocumentRequest],
) -> None:
    documents_by_version = {document.version_id: document for document in documents}
    for result in results:
        document = documents_by_version.get(result.version_id)
        if document is None or not _requires_proposal_generation(result, document):
            continue
        result.proposal = result.proposal.model_copy(
            update={
                "draft_status": ProposalDraftStatus.GENERATING,
                "draft_reason": (
                    "공고 분석을 먼저 완료했습니다. 사업 제안은 백그라운드에서 준비하고 있습니다."
                ),
            }
        )


class _CompletedBaseWorkflow:
    def __init__(self, result: DocumentAnalysisResult) -> None:
        self._result = result

    async def analyze(self, _document: AnalysisDocumentRequest) -> DocumentAnalysisResult:
        return self._result


async def _generate_and_deliver_proposals(
    job_id: UUID,
    request: AnalysisJobRequest,
    base_results: list[DocumentAnalysisResult],
    proposal_runner: LangChainProposalGenerationRunner,
    result_client: AnalysisResultClient,
    concurrency: int,
) -> None:
    results_by_version = {result.version_id: result for result in base_results}
    semaphore = asyncio.Semaphore(concurrency)

    async def generate_one(
        document: AnalysisDocumentRequest,
    ) -> ProposalUpdateResult | None:
        base_result = results_by_version.get(document.version_id)
        if base_result is None:
            return None
        workflow = TwoStageAnalysisWorkflow(
            base_workflow=_CompletedBaseWorkflow(base_result),
            proposal_runner=proposal_runner,
        )
        async with semaphore:
            completed = await workflow.analyze(document)
        if completed.proposal.preparation_schema_version != 10:
            return None
        return ProposalUpdateResult(
            detection_id=document.detection_id,
            document_id=document.document_id,
            version_id=document.version_id,
            proposal=completed.proposal,
            used_tools=completed.used_tools,
        )

    updates = [
        update
        for update in await asyncio.gather(
            *(generate_one(document) for document in request.documents)
        )
        if update is not None
    ]
    response = await result_client.send_proposals(
        ProposalResultRequest(run_id=request.run_id, job_id=job_id, results=updates)
    )
    logger.info(
        "사업 제안 결과 전달 완료. run_id=%s job_id=%s updated_count=%s",
        request.run_id,
        job_id,
        response.data.updated_proposal_count,
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
