import asyncio
from datetime import datetime
from uuid import UUID

import httpx

from app.domains.monitoring.clients import CollectionResultClient
from app.domains.monitoring.schemas.collected_document import (
    CollectedAttachment,
    CollectedDocument,
    SourceCollectionResult,
)
from app.domains.monitoring.schemas.collection_result import CollectionResultRequest


def test_convert_collected_results_to_camel_case_request() -> None:
    request = CollectionResultRequest.from_collected(
        10,
        UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        [
            SourceCollectionResult(
                source_id=1,
                documents=[
                    CollectedDocument(
                        original_url="https://example.com/notice/1",
                        external_document_id="1",
                        title="지원사업 공고",
                        content_text="본문",
                        published_at=datetime(2026, 8, 18, 9, 0),
                        attachments=[
                            CollectedAttachment(
                                file_name="공고.pdf",
                                download_url="https://example.com/file/1",
                            )
                        ],
                    )
                ],
            )
        ],
    )

    body = request.model_dump(by_alias=True, mode="json")

    assert body["runId"] == 10
    assert body["sources"][0]["status"] == "COMPLETED"
    assert body["sources"][0]["documents"][0]["publishedAt"] == "2026-08-18T09:00:00"
    assert body["sources"][0]["documents"][0]["attachments"][0]["fileName"] == "공고.pdf"


def test_send_collection_result_to_spring_boot() -> None:
    captured_body: dict[str, object] = {}

    def handle(request: httpx.Request) -> httpx.Response:
        captured_body.update(__import__("json").loads(request.content))
        return httpx.Response(
            200,
            json={
                "isSuccess": True,
                "code": "SUCCESS_200",
                "httpStatus": 200,
                "message": "요청에 성공했습니다.",
                "data": {
                    "documents": [
                        {
                            "originalUrl": "https://example.com/notice/1",
                            "documentId": 100,
                            "versionId": 200,
                            "changeType": "NEW_DOCUMENT",
                            "analysisRequired": True,
                        }
                    ]
                },
            },
        )

    request = CollectionResultRequest.from_collected(
        10,
        UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        [SourceCollectionResult(source_id=1)],
    )
    client = CollectionResultClient(
        base_url="http://spring.test",
        transport=httpx.MockTransport(handle),
    )

    response = asyncio.run(client.send(request))

    assert captured_body["runId"] == 10
    assert response.data.documents[0].document_id == 100
    assert response.data.documents[0].analysis_required is True
