import asyncio
import hashlib

import httpx

from app.domains.monitoring.downloaders import AttachmentDownloader
from app.domains.monitoring.schemas.collected_document import (
    AttachmentParseStatus,
    CollectedAttachment,
    CollectedDocument,
    SourceCollectionResult,
)


def _result() -> list[SourceCollectionResult]:
    return [
        SourceCollectionResult(
            source_id=1,
            documents=[
                CollectedDocument(
                    original_url="https://example.go.kr/notices/1",
                    title="지원사업 공고",
                    attachments=[
                        CollectedAttachment(
                            file_name="공고문.hwp",
                            download_url="https://example.go.kr/files/1",
                        )
                    ],
                )
            ],
        )
    ]


def test_download_attachment_calculates_metadata() -> None:
    content = b"test attachment content"
    captured_referer = ""

    def handle(request: httpx.Request) -> httpx.Response:
        nonlocal captured_referer
        captured_referer = request.headers["referer"]
        return httpx.Response(
            200,
            headers={"content-type": "application/x-hwp; charset=binary"},
            content=content,
        )

    results = asyncio.run(
        AttachmentDownloader(transport=httpx.MockTransport(handle)).enrich_results(_result())
    )
    attachment = results[0].documents[0].attachments[0]

    assert captured_referer == "https://example.go.kr/notices/1"
    assert attachment.content_type == "application/x-hwp"
    assert attachment.file_size == len(content)
    assert attachment.file_hash == hashlib.sha256(content).hexdigest()
    assert attachment.parse_status == AttachmentParseStatus.PENDING
    assert attachment.error_message is None


def test_oversized_attachment_is_marked_failed() -> None:
    def handle(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"12345")

    results = asyncio.run(
        AttachmentDownloader(
            max_size_bytes=4,
            transport=httpx.MockTransport(handle),
        ).enrich_results(_result())
    )
    attachment = results[0].documents[0].attachments[0]

    assert attachment.parse_status == AttachmentParseStatus.FAILED
    assert attachment.file_size is None
    assert attachment.file_hash is None
    assert attachment.error_message == "첨부파일이 허용된 최대 크기를 초과했습니다."


def test_http_failure_is_marked_failed_without_exposing_url() -> None:
    def handle(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    results = asyncio.run(
        AttachmentDownloader(transport=httpx.MockTransport(handle)).enrich_results(_result())
    )
    attachment = results[0].documents[0].attachments[0]

    assert attachment.parse_status == AttachmentParseStatus.FAILED
    assert attachment.error_message == "첨부파일 서버가 HTTP 404 상태를 반환했습니다."
    assert attachment.download_url not in attachment.error_message