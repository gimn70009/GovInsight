import asyncio
from uuid import UUID

import pytest

from app.domains.monitoring.schemas.collected_document import SourceCollectionResult
from app.domains.monitoring.schemas.request import MonitoringJobRequest
from app.domains.monitoring.tasks import run_monitoring_job


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
