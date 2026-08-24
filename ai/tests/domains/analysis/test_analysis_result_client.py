import asyncio
import json
from uuid import UUID

import httpx
import pytest

from app.domains.analysis.clients import AnalysisResultClient, AnalysisResultClientError
from app.domains.analysis.schemas.delivery import AnalysisResultRequest
from app.domains.analysis.schemas.result import (
    DocumentAnalysisResult,
    DocumentImportance,
    Eligibility,
    Favorability,
)


def analysis_request() -> AnalysisResultRequest:
    return AnalysisResultRequest(
        run_id=10,
        job_id=UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        results=[
            DocumentAnalysisResult(
                detection_id=20,
                document_id=30,
                version_id=40,
                summary="기업 지원사업의 신청 대상과 기한을 정리한 분석 결과입니다.",
                key_points=["신청 기한 확인", "지원 대상 검토"],
                importance=DocumentImportance.HIGH,
                reason="접수 기한이 명시되어 있어 빠른 검토가 필요합니다.",
                eligibility=Eligibility.REVIEW_REQUIRED,
                favorable_or_not=Favorability.NOT_APPLICABLE,
                proposal_direction="제조 AI 기술을 활용한 사업 제안 가능성을 검토합니다.",
                used_tools=["get_document_content"],
                model_name="gpt-5-mini",
            )
        ],
        failures=[],
    )


def success_response() -> dict[str, object]:
    return {
        "isSuccess": True,
        "code": "SUCCESS_200",
        "httpStatus": 200,
        "message": "요청에 성공했습니다.",
        "data": {
            "runId": 10,
            "storedAnalysisCount": 1,
            "duplicateAnalysisCount": 0,
            "failedAnalysisCount": 0,
        },
    }


def test_send_analysis_result_in_camel_case() -> None:
    captured_body: dict[str, object] = {}

    def handle(request: httpx.Request) -> httpx.Response:
        captured_body.update(json.loads(request.content))
        return httpx.Response(200, json=success_response())

    client = AnalysisResultClient(
        base_url="http://spring.test",
        transport=httpx.MockTransport(handle),
    )

    response = asyncio.run(client.send(analysis_request()))

    assert captured_body["runId"] == 10
    assert captured_body["results"][0]["detectionId"] == 20
    assert response.data.stored_analysis_count == 1


def test_retry_server_error_then_succeed() -> None:
    call_count = 0

    def handle(_request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            return httpx.Response(503)
        return httpx.Response(200, json=success_response())

    client = AnalysisResultClient(
        base_url="http://spring.test",
        max_attempts=2,
        retry_delay_seconds=0,
        transport=httpx.MockTransport(handle),
    )

    asyncio.run(client.send(analysis_request()))

    assert call_count == 2


def test_do_not_retry_client_error() -> None:
    call_count = 0

    def handle(_request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(409)

    client = AnalysisResultClient(
        base_url="http://spring.test",
        max_attempts=3,
        retry_delay_seconds=0,
        transport=httpx.MockTransport(handle),
    )

    with pytest.raises(AnalysisResultClientError):
        asyncio.run(client.send(analysis_request()))

    assert call_count == 1
