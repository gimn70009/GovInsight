import asyncio
import logging
import sys
from uuid import UUID

from app.domains.monitoring.clients import CollectionResultClient
from app.domains.monitoring.collectors import PlaywrightCollector
from app.domains.monitoring.downloaders import AttachmentDownloader
from app.domains.monitoring.schemas.collected_document import (
    CollectedDocument,
    SourceCollectionResult,
)
from app.domains.monitoring.schemas.collection_result import CollectionResultRequest
from app.domains.monitoring.schemas.request import MonitoringJobRequest, MonitoringSourceRequest

logger = logging.getLogger(__name__)


async def run_monitoring_job(job_id: UUID, request: MonitoringJobRequest) -> None:
    logger.info(
        "모니터링 백그라운드 작업 시작. run_id=%s job_id=%s source_count=%s",
        request.run_id,
        job_id,
        len(request.sources),
    )

    try:
        results = await asyncio.to_thread(_collect_sources, request.sources)
    except Exception:
        logger.exception(
            "모니터링 백그라운드 수집 실행 실패. run_id=%s job_id=%s",
            request.run_id,
            job_id,
        )
        return

    results = await AttachmentDownloader().enrich_results(results)

    for result in results:
        if result.succeeded:
            logger.info(
                "모니터링 소스 수집 완료. run_id=%s job_id=%s source_id=%s document_count=%s",
                request.run_id,
                job_id,
                result.source_id,
                len(result.documents),
            )
            _log_collected_documents(request.run_id, job_id, result.source_id, result.documents)
        else:
            logger.warning(
                "모니터링 소스 수집 실패. run_id=%s job_id=%s source_id=%s error=%s",
                request.run_id,
                job_id,
                result.source_id,
                result.error_message,
            )

    collection_request = CollectionResultRequest.from_collected(
        request.run_id,
        job_id,
        results,
    )
    try:
        response = await CollectionResultClient().send(collection_request)
    except Exception:
        logger.exception(
            "Spring Boot 수집 결과 전달 실패. run_id=%s job_id=%s",
            request.run_id,
            job_id,
        )
        return

    logger.info(
        "모니터링 수집 결과 저장 완료. run_id=%s job_id=%s stored_document_count=%s",
        request.run_id,
        job_id,
        len(response.data.documents),
    )


def _log_collected_documents(
    run_id: int,
    job_id: UUID,
    source_id: int,
    documents: list[CollectedDocument],
) -> None:
    for document in documents:
        logger.info(
            "수집 문서 확인. run_id=%s job_id=%s source_id=%s title=%s "
            "published_at=%s content_length=%s attachment_count=%s url=%s",
            run_id,
            job_id,
            source_id,
            document.title,
            document.published_at,
            len(document.content_text or ""),
            len(document.attachments),
            document.original_url,
        )

        for attachment in document.attachments:
            logger.info(
                "수집 첨부파일 확인. run_id=%s job_id=%s source_id=%s "
                "file_name=%s parse_status=%s file_size=%s",
                run_id,
                job_id,
                source_id,
                attachment.file_name,
                attachment.parse_status,
                attachment.file_size,
            )


def _collect_sources(
    sources: list[MonitoringSourceRequest],
) -> list[SourceCollectionResult]:
    if sys.platform == "win32":
        loop = asyncio.ProactorEventLoop()
    else:
        loop = asyncio.new_event_loop()

    try:
        asyncio.set_event_loop(loop)
        return loop.run_until_complete(PlaywrightCollector().collect_sources(sources))
    finally:
        loop.close()
