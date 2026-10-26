import asyncio
from uuid import UUID

import pytest

from app.domains.monitoring.schemas.collected_document import (
    CollectedAttachment,
    CollectedDocument,
    SourceCollectionResult,
)
from app.domains.monitoring.schemas.request import MonitoringJobRequest
from app.domains.monitoring.tasks import run_monitoring_job


@pytest.fixture(autouse=True)
def prevent_real_result_delivery(monkeypatch: pytest.MonkeyPatch) -> None:
    async def send_result(_client, _request):
        return type("Response", (), {"data": type("Data", (), {"documents": []})()})()

    monkeypatch.setattr(
        "app.domains.monitoring.tasks.CollectionResultClient.send",
        send_result,
    )


def test_run_monitoring_job_collects_every_source(monkeypatch: pytest.MonkeyPatch) -> None:
    collected_source_ids: list[int] = []

    async def collect_sources(_collector, sources):
        collected_source_ids.extend(source.source_id for source in sources)
        return [SourceCollectionResult(source_id=source.source_id) for source in sources]

    monkeypatch.setattr("app.domains.monitoring.tasks.sys.platform", "linux")
    monkeypatch.setattr(
        "app.domains.monitoring.tasks.PlaywrightCollector.collect_sources", collect_sources
    )
    request = MonitoringJobRequest.model_validate(
        {
            "runId": 5,
            "sources": [
                {
                    "sourceId": 1,
                    "organizationName": "서울시",
                    "boardName": "공지사항",
                    "listUrl": "https://example.go.kr/notices",
                    "urlIncludePattern": "/notice/view",
                    "detailFetchCount": 3,
                },
                {
                    "sourceId": 2,
                    "organizationName": "환경부",
                    "boardName": "보도자료",
                    "listUrl": "https://example.go.kr/press",
                    "urlIncludePattern": "/press/view",
                    "detailFetchCount": 3,
                },
            ],
        }
    )

    asyncio.run(
        run_monitoring_job(
            UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
            request,
        )
    )

    assert collected_source_ids == [1, 2]


def test_run_monitoring_job_logs_document_metadata_without_content(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    logged_messages: list[str] = []

    def capture_log(message: str, *args) -> None:
        logged_messages.append(message % args)

    async def collect_sources(_collector, _sources):
        return [
            SourceCollectionResult(
                source_id=1,
                documents=[
                    CollectedDocument(
                        original_url="https://example.go.kr/notices/1",
                        title="테스트 공고",
                        content_text="로그에 남기면 안 되는 게시글 원문",
                        attachments=[
                            CollectedAttachment(
                                file_name="공고문.pdf",
                                download_url="https://example.go.kr/files/1",
                            )
                        ],
                    )
                ],
            )
        ]

    monkeypatch.setattr("app.domains.monitoring.tasks.sys.platform", "linux")
    monkeypatch.setattr(
        "app.domains.monitoring.tasks.PlaywrightCollector.collect_sources", collect_sources
    )
    monkeypatch.setattr("app.domains.monitoring.tasks.logger.info", capture_log)
    request = MonitoringJobRequest.model_validate(
        {
            "runId": 5,
            "sources": [
                {
                    "sourceId": 1,
                    "organizationName": "서울시",
                    "boardName": "공지사항",
                    "listUrl": "https://example.go.kr/notices",
                    "urlIncludePattern": "/notice/view",
                    "detailFetchCount": 1,
                }
            ],
        }
    )

    asyncio.run(
        run_monitoring_job(
            UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
            request,
        )
    )

    log_text = "\n".join(logged_messages)
    assert "title=테스트 공고" in log_text
    assert "content_length=19" in log_text
    assert "attachment_count=1" in log_text
    assert "file_name=공고문.pdf" in log_text
    assert "로그에 남기면 안 되는 게시글 원문" not in log_text


@pytest.fixture(autouse=True)
def prevent_real_attachment_download(monkeypatch: pytest.MonkeyPatch) -> None:
    async def enrich_results(_downloader, results):
        return results

    monkeypatch.setattr(
        "app.domains.monitoring.tasks.AttachmentDownloader.enrich_results",
        enrich_results,
    )

@pytest.mark.parametrize("stage", ["collection", "attachments", "serialization"])
def test_unexpected_failure_delivers_failed_sources_without_raw_error(monkeypatch, stage):
    delivered = []

    def collect(sources):
        if stage == "collection":
            raise RuntimeError("private error details")
        if stage == "serialization":
            return []  # Empty source results cannot be serialized as a valid callback.
        return [SourceCollectionResult(source_id=source.source_id) for source in sources]

    async def enrich(_downloader, results):
        if stage == "attachments":
            raise RuntimeError("private error details")
        return results

    async def send(_client, request):
        delivered.append(request)
        return type("Response", (), {"data": type("Data", (), {"documents": []})()})()

    monkeypatch.setattr("app.domains.monitoring.tasks._collect_sources", collect)
    monkeypatch.setattr("app.domains.monitoring.tasks.AttachmentDownloader.enrich_results", enrich)
    monkeypatch.setattr("app.domains.monitoring.tasks.CollectionResultClient.send", send)
    request = MonitoringJobRequest.model_validate({
        "runId": 5,
        "sources": [{
            "sourceId": source_id, "organizationName": "기관", "boardName": "공고",
            "listUrl": "https://example.org", "detailFetchCount": 1,
        } for source_id in [1, 2]],
    })
    job_id = UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202")
    asyncio.run(run_monitoring_job(job_id, request))
    assert len(delivered) == 1
    assert delivered[0].run_id == 5
    assert delivered[0].job_id == job_id
    assert [source.source_id for source in delivered[0].sources] == [1, 2]
    assert all(source.status == "FAILED" for source in delivered[0].sources)
    assert all(source.documents == [] for source in delivered[0].sources)
    assert "private error details" not in delivered[0].model_dump_json()
