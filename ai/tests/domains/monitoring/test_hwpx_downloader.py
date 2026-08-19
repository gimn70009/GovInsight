import asyncio
import io
import zipfile

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
                            file_name="공고문.hwpx [123 KB]",
                            download_url="https://example.go.kr/files/1",
                        )
                    ],
                )
            ],
        )
    ]


def test_hwpx_is_parsed_during_temporary_download() -> None:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(
            "Contents/section0.xml",
            '<section><p><run><t>지원사업 공고 본문</t></run></p></section>',
        )

    def handle(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=buffer.getvalue())

    results = asyncio.run(
        AttachmentDownloader(transport=httpx.MockTransport(handle)).enrich_results(_result())
    )
    attachment = results[0].documents[0].attachments[0]

    assert attachment.extracted_text == "지원사업 공고 본문"
    assert attachment.parse_status == AttachmentParseStatus.COMPLETED
    assert attachment.error_message is None


def test_broken_hwpx_is_marked_failed() -> None:
    def handle(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"broken hwpx")

    results = asyncio.run(
        AttachmentDownloader(transport=httpx.MockTransport(handle)).enrich_results(_result())
    )
    attachment = results[0].documents[0].attachments[0]

    assert attachment.extracted_text is None
    assert attachment.parse_status == AttachmentParseStatus.FAILED
    assert attachment.error_message == "HWPX 파일을 읽을 수 없습니다."
