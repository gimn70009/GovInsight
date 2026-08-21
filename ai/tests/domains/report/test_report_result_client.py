import asyncio
import json
from uuid import UUID

import httpx
import pytest

from app.domains.report.clients import ReportResultClient, ReportResultClientError
from app.domains.report.schemas.delivery import ReportResultRequest, ReportResultStatus


def report_request() -> ReportResultRequest:
    return ReportResultRequest(
        run_id=10,
        job_id=UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        status=ReportResultStatus.COMPLETED,
        title="산업통상부 사업공고 모니터링 요약",
        summary="신규 지원사업 공고 한 건이 감지되어 신청 대상과 기한을 정리했습니다.",
    )


def success_response() -> dict[str, object]:
    return {
        "isSuccess": True,
        "code": "SUCCESS_200",
        "httpStatus": 200,
        "message": "요청에 성공했습니다.",
        "data": {
            "runId": 10,
            "reportId": 50,
            "status": "COMPLETED",
            "duplicate": False,
        },
    }


def test_send_report_result_in_camel_case() -> None:
    captured_body: dict[str, object] = {}

    def handle(request: httpx.Request) -> httpx.Response:
        captured_body.update(json.loads(request.content))
        return httpx.Response(200, json=success_response())

    client = ReportResultClient(
        base_url="http://spring.test",
        transport=httpx.MockTransport(handle),
    )

    response = asyncio.run(client.send(report_request()))

    assert captured_body["runId"] == 10
    assert captured_body["status"] == "COMPLETED"
    assert response.data.report_id == 50


def test_retry_server_error_then_succeed() -> None:
    call_count = 0

    def handle(_request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            return httpx.Response(503)
        return httpx.Response(200, json=success_response())

    client = ReportResultClient(
        base_url="http://spring.test",
        max_attempts=2,
        retry_delay_seconds=0,
        transport=httpx.MockTransport(handle),
    )

    asyncio.run(client.send(report_request()))

    assert call_count == 2


def test_do_not_retry_client_error() -> None:
    client = ReportResultClient(
        base_url="http://spring.test",
        max_attempts=3,
        retry_delay_seconds=0,
        transport=httpx.MockTransport(lambda _request: httpx.Response(409)),
    )

    with pytest.raises(ReportResultClientError):
        asyncio.run(client.send(report_request()))
