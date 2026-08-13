from unittest.mock import call
from uuid import UUID

import pytest

from app.domains.monitoring.schemas.request import MonitoringJobRequest
from app.domains.monitoring.tasks import run_monitoring_job


def test_run_monitoring_job_processes_every_source(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    processed_sources = []

    def record_source(run_id, job_id, source) -> None:
        processed_sources.append(call(run_id, job_id, source.source_id))

    monkeypatch.setattr(
        "app.domains.monitoring.tasks._process_monitoring_source",
        record_source,
    )
    job_id = UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202")
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

    run_monitoring_job(job_id, request)

    assert processed_sources == [
        call(5, job_id, 1),
        call(5, job_id, 2),
    ]
