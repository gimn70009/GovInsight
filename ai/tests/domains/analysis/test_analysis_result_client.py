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
                        OpportunityDimension(
                            type=OpportunityDimensionType.EVIDENCE_CONFIDENCE,
                            score=65,
                            reason="회사 규모 정보가 없어 일부 조건은 추가 확인이 필요합니다.",
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
