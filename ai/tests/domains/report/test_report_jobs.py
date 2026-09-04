from unittest.mock import Mock
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def valid_request() -> dict[str, object]:
    return {
        "runId": 10,
        "totalSourceCount": 2,
        "detectedDocumentCount": 1,
        "warningCount": 0,
        "documents": [
            {
                "detectionId": 20,
                "documentId": 30,
                "versionId": 40,
                "organizationName": "산업통상부",
                "boardName": "사업공고",
                "changeType": "NEW_DOCUMENT",
                "title": "중소기업 지원사업 공고",
                "publishedAt": "2026-08-21T09:00:00",
                "originalUrl": "https://example.go.kr/notice/1",
                "summary": "중소기업 지원 대상과 접수 기한을 안내하는 공고입니다.",
                "keyPoints": ["접수 기한 확인", "지원 대상 검토"],
                "importance": "HIGH",
                "reason": "접수 기한이 있어 빠른 검토가 필요합니다.",
            }
        ],
    }


def test_accept_report_job(monkeypatch: pytest.MonkeyPatch) -> None:
    background_job = Mock()
    monkeypatch.setattr("app.domains.report.api.run_report_job", background_job)

    response = client.post("/internal/monitoring/report-jobs", json=valid_request())

    assert response.status_code == 202
    assert response.json()["status"] == "ACCEPTED"
    assert response.json()["documentCount"] == 1
    job_id = UUID(response.json()["jobId"])
    background_job.assert_called_once()
    scheduled_job_id, scheduled_request = background_job.call_args.args
    assert scheduled_job_id == job_id
    assert scheduled_request.run_id == 10
    assert scheduled_request.documents[0].importance == "HIGH"


def test_reject_report_job_without_documents() -> None:
    request = valid_request()
    request["documents"] = []

    response = client.post("/internal/monitoring/report-jobs", json=request)

    assert response.status_code == 422
