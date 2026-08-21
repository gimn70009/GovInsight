from unittest.mock import Mock
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def valid_request() -> dict[str, object]:
    return {
        "runId": 10,
        "documents": [
            {
                "detectionId": 25,
                "documentId": 8,
                "versionId": 12,
                "changeType": "NEW_DOCUMENT",
                "organizationName": "산업통상부",
                "boardName": "사업공고",
                "title": "중소기업 지원사업 공고",
                "contentText": "지원 대상과 접수 방법에 관한 본문입니다.",
                "publishedAt": "2026-08-21T09:00:00",
                "originalUrl": "https://example.go.kr/board/view.do?id=123",
                "attachments": [
                    {
                        "attachmentId": 30,
                        "fileName": "사업공고.hwpx",
                        "extractedText": "첨부파일에서 추출한 신청 기간입니다.",
                    }
                ],
                "previousVersion": None,
            }
        ],
    }


def test_accept_analysis_job(monkeypatch: pytest.MonkeyPatch) -> None:
    background_job = Mock()
    monkeypatch.setattr(
        "app.domains.analysis.api.run_analysis_job",
        background_job,
    )

    response = client.post("/internal/monitoring/analysis-jobs", json=valid_request())

    assert response.status_code == 202
    assert response.json()["status"] == "ACCEPTED"
    assert response.json()["documentCount"] == 1
    job_id = UUID(response.json()["jobId"])

    background_job.assert_called_once()
    scheduled_job_id, scheduled_request = background_job.call_args.args
    assert scheduled_job_id == job_id
    assert scheduled_request.run_id == 10
    assert scheduled_request.documents[0].version_id == 12


def test_reject_analysis_job_without_documents() -> None:
    request = valid_request()
    request["documents"] = []

    response = client.post("/internal/monitoring/analysis-jobs", json=request)

    assert response.status_code == 422


def test_reject_document_without_analyzable_text() -> None:
    request = valid_request()
    documents = request["documents"]
    assert isinstance(documents, list)
    documents[0]["contentText"] = None
    documents[0]["attachments"] = []

    response = client.post("/internal/monitoring/analysis-jobs", json=request)

    assert response.status_code == 422
