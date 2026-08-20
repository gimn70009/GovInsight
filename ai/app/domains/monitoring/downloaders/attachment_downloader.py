import hashlib
import logging
import tempfile
from dataclasses import dataclass
from pathlib import Path

import httpx

from app.core.config import ATTACHMENT_DOWNLOAD_TIMEOUT_SECONDS, ATTACHMENT_MAX_SIZE_BYTES
from app.domains.monitoring.parsers import AttachmentParseError, AttachmentParserRegistry
from app.domains.monitoring.schemas.collected_document import (
    AttachmentParseStatus,
    CollectedAttachment,
    CollectedDocument,
    SourceCollectionResult,
)

logger = logging.getLogger(__name__)


class AttachmentTooLargeError(Exception):
    pass


@dataclass(frozen=True)
class DownloadedMetadata:
    content_type: str | None
    file_size: int
    file_hash: str


class AttachmentDownloader:
    def __init__(
        self,
        timeout_seconds: float = ATTACHMENT_DOWNLOAD_TIMEOUT_SECONDS,
        max_size_bytes: int = ATTACHMENT_MAX_SIZE_BYTES,
        transport: httpx.AsyncBaseTransport | None = None,
        parser_registry: AttachmentParserRegistry | None = None,
    ) -> None:
        self.timeout_seconds = timeout_seconds
        self.max_size_bytes = max_size_bytes
        self.transport = transport
        self.parser_registry = parser_registry or AttachmentParserRegistry()

    async def enrich_results(
        self,
        results: list[SourceCollectionResult],
    ) -> list[SourceCollectionResult]:
        async with httpx.AsyncClient(
            follow_redirects=True,
            timeout=self.timeout_seconds,
            transport=self.transport,
            headers={"User-Agent": "GovInsight/1.0"},
        ) as client:
            return [await self._enrich_source(client, result) for result in results]

    async def _enrich_source(
        self,
        client: httpx.AsyncClient,
        result: SourceCollectionResult,
    ) -> SourceCollectionResult:
        documents = [await self._enrich_document(client, document) for document in result.documents]
        return result.model_copy(update={"documents": documents})

    async def _enrich_document(
        self,
        client: httpx.AsyncClient,
        document: CollectedDocument,
    ) -> CollectedDocument:
        attachments = [
            await self._download(client, attachment, document.original_url)
            for attachment in document.attachments
        ]
        return document.model_copy(update={"attachments": attachments})

    async def _download(
        self,
        client: httpx.AsyncClient,
        attachment: CollectedAttachment,
        referer: str,
    ) -> CollectedAttachment:
        metadata: DownloadedMetadata | None = None
        try:
            with tempfile.TemporaryDirectory(prefix="govinsight-attachment-") as directory:
                file_path = Path(directory) / "download"
                metadata = await self._stream_to_file(
                    client,
                    attachment.download_url,
                    referer,
                    file_path,
                )
                parser = self.parser_registry.find(attachment.file_name)
                parsed = parser.parse(file_path) if parser is not None else None
            return attachment.model_copy(
                update={
                    "content_type": metadata.content_type,
                    "file_size": metadata.file_size,
                    "file_hash": metadata.file_hash,
                    "extracted_text": parsed.text if parsed is not None else None,
                    "parse_status": _successful_parse_status(attachment.file_name, parsed),
                    "error_message": None,
                }
            )
        except (
            httpx.HTTPError,
            OSError,
            AttachmentTooLargeError,
            AttachmentParseError,
        ) as exception:
            logger.warning(
                "첨부파일 다운로드 또는 파싱 실패. file_name=%s reason=%s",
                attachment.file_name,
                type(exception).__name__,
            )
            return attachment.model_copy(
                update={
                    "content_type": metadata.content_type if metadata is not None else None,
                    "file_size": metadata.file_size if metadata is not None else None,
                    "file_hash": metadata.file_hash if metadata is not None else None,
                    "extracted_text": None,
                    "parse_status": AttachmentParseStatus.FAILED,
                    "error_message": _safe_error_message(exception),
                }
            )

    async def _stream_to_file(
        self,
        client: httpx.AsyncClient,
        download_url: str,
        referer: str,
        file_path: Path,
    ) -> DownloadedMetadata:
        digest = hashlib.sha256()
        file_size = 0

        async with client.stream("GET", download_url, headers={"Referer": referer}) as response:
            response.raise_for_status()
            declared_size = _content_length(response)
            if declared_size is not None and declared_size > self.max_size_bytes:
                raise AttachmentTooLargeError

            with file_path.open("wb") as output:
                async for chunk in response.aiter_bytes():
                    file_size += len(chunk)
                    if file_size > self.max_size_bytes:
                        raise AttachmentTooLargeError
                    output.write(chunk)
                    digest.update(chunk)

            return DownloadedMetadata(
                content_type=_content_type(response),
                file_size=file_size,
                file_hash=digest.hexdigest(),
            )


def _successful_parse_status(file_name: str, parsed: object | None) -> AttachmentParseStatus:
    if parsed is not None:
        return AttachmentParseStatus.COMPLETED
    return AttachmentParseStatus.UNSUPPORTED


def _content_length(response: httpx.Response) -> int | None:
    value = response.headers.get("content-length")
    try:
        return int(value) if value is not None else None
    except ValueError:
        return None


def _content_type(response: httpx.Response) -> str | None:
    value = response.headers.get("content-type")
    if value is None:
        return None
    normalized = value.split(";", maxsplit=1)[0].strip().lower()
    return normalized[:200] or None


def _safe_error_message(exception: Exception) -> str:
    if isinstance(exception, AttachmentTooLargeError):
        return "첨부파일이 허용된 최대 크기를 초과했습니다."
    if isinstance(exception, httpx.TimeoutException):
        return "첨부파일 다운로드 시간이 초과되었습니다."
    if isinstance(exception, httpx.HTTPStatusError):
        return f"첨부파일 서버가 HTTP {exception.response.status_code} 상태를 반환했습니다."
    if isinstance(exception, AttachmentParseError):
        return str(exception)
    return "첨부파일을 다운로드하지 못했습니다."