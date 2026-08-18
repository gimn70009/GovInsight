from uuid import UUID

from app.domains.monitoring.schemas.collected_document import (
    AttachmentParseStatus,
    CollectedAttachment,
    CollectedDocument,
    SourceCollectionResult,
)
from app.domains.monitoring.schemas.collection_result import CollectionResultRequest


def test_collection_result_contains_extracted_hwpx_text() -> None:
    request = CollectionResultRequest.from_collected(
        run_id=1,
        job_id=UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        results=[
            SourceCollectionResult(
                source_id=1,
                documents=[
                    CollectedDocument(
                        original_url="https://example.go.kr/notices/1",
                        title="지원사업 공고",
                        attachments=[
                            CollectedAttachment(
                                file_name="공고.hwpx",
                                download_url="https://example.go.kr/files/1",
                                extracted_text="추출된 HWPX 본문",
                                parse_status=AttachmentParseStatus.COMPLETED,
                            )
                        ],
                    )
                ],
            )
        ],
    )

    body = request.model_dump(mode="json", by_alias=True)
    attachment = body["sources"][0]["documents"][0]["attachments"][0]

    assert attachment["extractedText"] == "추출된 HWPX 본문"
    assert attachment["parseStatus"] == "COMPLETED"
