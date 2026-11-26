import asyncio
import json
from uuid import UUID

import httpx
import pytest

from app.domains.analysis.clients import AnalysisResultClient, AnalysisResultClientError
from app.domains.analysis.schemas.delivery import (
    AnalysisResultRequest,
    ProposalResultRequest,
    ProposalUpdateResult,
)
from app.domains.analysis.schemas.result import (
    DocumentAnalysisResult,
    DocumentImportance,
    Eligibility,
    Favorability,
    OpportunityAssessment,
    OpportunityDimension,
    OpportunityDimensionType,
    ProposalSection,
    ProposalStrategy,
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
                proposal=ProposalStrategy(
                    sections=[
                        ProposalSection(
                            title="핵심 판단",
                            body="제조 AI 기술을 활용한 사업 제안 가능성을 검토합니다.",
                        )
                    ]
                ),
                opportunity=OpportunityAssessment(
                    dimensions=[
                        OpportunityDimension(
                            type=OpportunityDimensionType.COMPANY_FIT,
                            score=80,
                            reason="회사 산업 AI 기술과 공고 목적의 관련성이 높습니다.",
                        ),
                        OpportunityDimension(
                            type=OpportunityDimensionType.BUSINESS_VALUE,
                            score=70,
                            reason="사업 실적과 적용 사례 확보에 도움이 됩니다.",
                        ),
                        OpportunityDimension(
                            type=OpportunityDimensionType.FEASIBILITY,
                            score=60,
                            reason="지원 자격과 투입 인력을 추가로 확인해야 합니다.",
                        ),
                        OpportunityDimension(
                            type=OpportunityDimensionType.URGENCY,
                            score=90,
                            reason="신청 기한이 임박해 빠른 검토가 필요합니다.",
                        ),
                    ]
                ),
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
    assert captured_body["results"][0]["proposal"]["sections"][0]["title"] == "핵심 판단"
    assert captured_body["results"][0]["proposal"]["documentType"] == "REVIEW_REQUIRED"
    assert captured_body["results"][0]["proposal"]["draftStatus"] == "NOT_APPLICABLE"
    assert "document_type" not in captured_body["results"][0]["proposal"]
    assert captured_body["results"][0]["opportunity"]["dimensions"][0]["score"] == 80
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
    paths = []

    def handle(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        return httpx.Response(409)

    client = AnalysisResultClient(
        base_url="http://spring.test",
        max_attempts=3,
        retry_delay_seconds=0,
        transport=httpx.MockTransport(handle),
    )

    with pytest.raises(AnalysisResultClientError):
        asyncio.run(client.send(analysis_request()))

    assert paths == [
        "/internal/monitoring/analysis-results", "/internal/monitoring/analysis-result-failures",
    ]


def test_send_proposal_update_to_separate_endpoint() -> None:
    captured_path = ""
    captured_body: dict[str, object] = {}

    def handle(request: httpx.Request) -> httpx.Response:
        nonlocal captured_path
        captured_path = request.url.path
        captured_body.update(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "isSuccess": True,
                "code": "SUCCESS_200",
                "httpStatus": 200,
                "message": "요청에 성공했습니다.",
                "data": {"runId": 10, "updatedProposalCount": 1},
            },
        )

    analysis = analysis_request().results[0]
    request = ProposalResultRequest(
        run_id=10,
        job_id=UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        results=[
            ProposalUpdateResult(
                detection_id=analysis.detection_id,
                document_id=analysis.document_id,
                version_id=analysis.version_id,
                proposal=analysis.proposal,
                used_tools=["get_document_content", "build_proposal_preparation"],
            )
        ],
    )
    client = AnalysisResultClient(
        base_url="http://spring.test",
        transport=httpx.MockTransport(handle),
    )

    response = asyncio.run(client.send_proposals(request))

    assert captured_path == "/internal/monitoring/proposal-results"
    assert captured_body["results"][0]["proposal"]["sections"][0]["title"] == "핵심 판단"
    assert response.data.updated_proposal_count == 1
def test_proposal_completion_allows_empty_updates() -> None:
    request = ProposalResultRequest(
        run_id=10,
        job_id=UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        results=[],
    )

    assert request.model_dump(by_alias=True)["results"] == []


@pytest.mark.parametrize("phase", ["ANALYSIS", "PROPOSAL"])
def test_rejected_results_notify_exact_attempt_and_phase_without_sending_content(phase, caplog):
    bodies = []
    field = "results[12].comparisonSummary.legalRisks[2].evidenceExcerpt"

    def handle(request):
        bodies.append((request.url.path, json.loads(request.content)))
        if request.url.path.endswith("analysis-result-failures"):
            return httpx.Response(200, json={"data": {"applied": True}})
        return httpx.Response(400, json={
            "message": "private raw message",
            "data": {field: "private rejected text", "private\nkey": "secret"},
        })

    client = AnalysisResultClient(
        base_url="http://spring.test", transport=httpx.MockTransport(handle),
    )
    analysis = analysis_request()
    call = client.send(analysis) if phase == "ANALYSIS" else client.send_proposals(
        ProposalResultRequest(run_id=analysis.run_id, job_id=analysis.job_id, results=[]),
    )
    with pytest.raises(AnalysisResultClientError) as error:
        asyncio.run(call)
    assert bodies[-1] == ("/internal/monitoring/analysis-result-failures", {
        "runId": analysis.run_id, "jobId": str(analysis.job_id), "phase": phase, "httpStatus": 400,
    })
    assert len(bodies) == 2
    assert field in str(error.value)
    assert "private" not in str(error.value) + caplog.text
    assert "secret" not in str(error.value) + caplog.text


def test_failure_notice_retries_transport_and_server_errors_without_resending_results():
    paths = []

    def handle(request):
        paths.append(request.url.path)
        if len(paths) == 1:
            return httpx.Response(400)
        if len(paths) == 2:
            raise httpx.ReadTimeout("private connection detail")
        return httpx.Response(503 if len(paths) == 3 else 200)

    client = AnalysisResultClient(base_url="http://spring.test", retry_delay_seconds=0,
                                  transport=httpx.MockTransport(handle))
    with pytest.raises(AnalysisResultClientError, match="status=400"):
        asyncio.run(client.send(analysis_request()))
    assert paths.count("/internal/monitoring/analysis-results") == 1
    assert paths.count("/internal/monitoring/analysis-result-failures") == 3


def test_failed_failure_notice_keeps_original_error_and_bounds_attempts(caplog):
    paths = []

    def handle(request):
        paths.append(request.url.path)
        if len(paths) == 1:
            return httpx.Response(400, text="private raw body")
        raise httpx.ConnectError("private connection detail")

    client = AnalysisResultClient(base_url="http://spring.test", retry_delay_seconds=0,
                                  transport=httpx.MockTransport(handle))
    with pytest.raises(AnalysisResultClientError, match="status=400") as error:
        asyncio.run(client.send(analysis_request()))
    assert len(paths) == 4
    assert "private" not in str(error.value) + caplog.text


def test_rate_limit_does_not_report_permanent_failure():
    paths = []

    def handle(request):
        paths.append(request.url.path)
        return httpx.Response(429)

    client = AnalysisResultClient(
        base_url="http://spring.test", transport=httpx.MockTransport(handle),
    )
    with pytest.raises(AnalysisResultClientError, match="status=429"):
        asyncio.run(client.send(analysis_request()))
    assert paths == ["/internal/monitoring/analysis-results"]


def test_long_or_non_json_validation_response_is_never_included_in_error():
    from app.domains.analysis.clients.analysis_result_client import _rejection_details
    for response in (httpx.Response(400, text="private"),
                     httpx.Response(400, json={"data": {"results[0].summary": "private" * 3000}})):
        assert _rejection_details(response) == "status=400"
