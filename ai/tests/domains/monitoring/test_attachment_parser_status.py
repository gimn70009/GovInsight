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


def test_zip_entry_metadata_survives_download_and_spring_contract() -> None:
    import io
    import json
    import zipfile
    from uuid import uuid4

    from app.domains.monitoring.schemas.collection_result import CollectionResultRequest

    for failed in (False, True):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("표.xlsx", "지원하지 않음")
            if failed:
                archive.writestr("고장.hwp", "읽을 수 없음")
        results = [
            SourceCollectionResult(
                source_id=1,
                documents=[
                    CollectedDocument(
                        original_url="https://example.com/notice",
                        title="공고",
                        attachments=[
                            CollectedAttachment(
                                file_name="서류.zip", download_url="https://example.com/file"
                            )
                        ],
                    )
                ],
            )
        ]
        transport = httpx.MockTransport(lambda _: httpx.Response(200, content=data.getvalue()))
        enriched = asyncio.run(AttachmentDownloader(transport=transport).enrich_results(results))
        attachment = enriched[0].documents[0].attachments[0]
        assert attachment.parse_status == (
            AttachmentParseStatus.FAILED if failed else AttachmentParseStatus.UNSUPPORTED
        )
        assert not attachment.extracted_text
        assert json.loads(attachment.archive_entries_json)[0]["fileName"] == "표.xlsx"
        body = CollectionResultRequest.from_collected(1, uuid4(), enriched).model_dump(
            by_alias=True
        )
        assert (
            body["sources"][0]["documents"][0]["attachments"][0]["archiveEntriesJson"]
            == attachment.archive_entries_json
        )
