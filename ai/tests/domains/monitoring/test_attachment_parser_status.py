import asyncio

import httpx

from app.domains.monitoring.downloaders import AttachmentDownloader
from app.domains.monitoring.schemas.collected_document import (
    AttachmentParseStatus,
    CollectedAttachment,
    CollectedDocument,
    SourceCollectionResult,
)


def test_unknown_attachment_format_is_marked_unsupported() -> None:
    def handle(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"unknown content")

    results = [
        SourceCollectionResult(
            source_id=1,
            documents=[
                CollectedDocument(
                    original_url="https://example.go.kr/notices/1",
                    title="지원사업 공고",
                    attachments=[
                        CollectedAttachment(
                            file_name="첨부파일.xyz",
                            download_url="https://example.go.kr/files/1",
                        )
                    ],
                )
            ],
        )
    ]

    enriched = asyncio.run(
        AttachmentDownloader(transport=httpx.MockTransport(handle)).enrich_results(results)
    )
    attachment = enriched[0].documents[0].attachments[0]

    assert attachment.parse_status == AttachmentParseStatus.UNSUPPORTED
    assert attachment.error_message is None
