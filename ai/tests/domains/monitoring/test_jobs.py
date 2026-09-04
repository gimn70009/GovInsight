from unittest.mock import Mock
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def valid_request() -> dict[str, object]:
    return {
        "runId": 5,
        "sources": [
            {
                "sourceId": 1,
                "organizationName": "서울시",
                "boardName": "공지사항",
                "listUrl": "https://example.go.kr/notices",
                "urlIncludePattern": "/notice/view",
                "detailFetchCount": 3,
            }
        ],
    }


def test_accept_monitoring_job(monkeypatch: pytest.MonkeyPatch) -> None:
    background_job = Mock()
    monkeypatch.setattr(
        "app.domains.monitoring.api.run_monitoring_job",
        background_job,
    )

    response = client.post("/internal/monitoring/jobs", json=valid_request())

    assert response.status_code == 202
    assert response.json()["status"] == "ACCEPTED"
    job_id = UUID(response.json()["jobId"])

    background_job.assert_called_once()
    scheduled_job_id, scheduled_request = background_job.call_args.args
    assert scheduled_job_id == job_id
    assert scheduled_request.run_id == 5
    assert [source.source_id for source in scheduled_request.sources] == [1]


def test_reject_job_without_sources() -> None:
    request = valid_request()
    request["sources"] = []

    response = client.post("/internal/monitoring/jobs", json=request)

    assert response.status_code == 422


def test_reject_job_with_non_positive_detail_fetch_count() -> None:
    request = valid_request()
    sources = request["sources"]
    assert isinstance(sources, list)
    sources[0]["detailFetchCount"] = 0

    response = client.post("/internal/monitoring/jobs", json=request)

    assert response.status_code == 422


def test_reject_job_with_missing_required_field() -> None:
    request = valid_request()
    request.pop("runId")

    response = client.post("/internal/monitoring/jobs", json=request)

    assert response.status_code == 422
