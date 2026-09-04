from types import SimpleNamespace

from app.domains.analysis.schemas.result import StrategyDecision
from app.domains.report.schemas.request import ReportJobRequest
from app.domains.report.template import TemplateReportGenerator


def report_request() -> ReportJobRequest:
    return ReportJobRequest.model_validate(
        {
            "runId": 10,
            "requestedAt": "2026-09-02T08:30:00",
            "totalSourceCount": 2,
            "detectedDocumentCount": 3,
            "warningCount": 1,
            "documents": [
                {
                    "detectionId": 1,
                    "documentId": 1,
                    "versionId": 1,
                    "organizationName": "국토교통부",
                    "boardName": "공지사항",
                    "changeType": "UNCHANGED_DOCUMENT",
                    "title": "변경 없는 안내",
                    "originalUrl": "https://example.go.kr/1",
                    "summary": "기존 안내와 같은 내용입니다.",
                    "keyPoints": ["기존 조건이 유지됩니다."],
                    "importance": "LOW",
                    "reason": "별도 대응이 필요하지 않습니다.",
                },
                {
                    "detectionId": 2,
                    "documentId": 2,
                    "versionId": 2,
                    "organizationName": "산업통상부",
                    "boardName": "사업공고",
                    "changeType": "NEW_DOCUMENT",
                    "title": "중요 신규 지원사업",
                    "originalUrl": "https://example.go.kr/2",
                    "summary": "신청 기한이 있는 지원사업입니다.",
                    "keyPoints": ["9월 30일까지 신청합니다.", "지원 자격 검토가 필요합니다."],
                    "importance": "HIGH",
                    "reason": "회사 관련성과 대응 기한이 확인됩니다.",
                },
                {
                    "detectionId": 3,
                    "documentId": 3,
                    "versionId": 3,
                    "organizationName": "산업통상부",
                    "boardName": "사업공고",
                    "changeType": "UPDATED_DOCUMENT",
                    "title": "수정된 지원사업",
                    "originalUrl": "https://example.go.kr/3",
                    "summary": "접수 기간이 변경되었습니다.",
                    "keyPoints": [],
                    "importance": "NORMAL",
                    "reason": None,
                },
            ],
        }
    )


def test_generate_report_without_model_call() -> None:
    request = report_request()
    first = request.documents[0].model_copy(
        update={
            "opportunity_score": 82,
            "proposal": SimpleNamespace(
                preparation=SimpleNamespace(
                    application_deadline="2026-09-30",
                    strategy=SimpleNamespace(
                        decision=StrategyDecision.CONDITIONAL_GO,
                        critical_gaps=[
                            SimpleNamespace(
                                owner="사업개발팀",
                                target_date="2026-09-05",
                                target_timing="내부 검토 후",
                                next_action="신청기업 자격을 확인합니다.",
                            )
                        ],
                    ),
                )
            ),
        }
    )
    request = request.model_copy(update={"documents": [first, *request.documents[1:]]})

    draft = TemplateReportGenerator().generate(request)

    assert draft.title == "[공공기관 모니터링] 9월 2일 보고서"
    assert "신규 1건 │ 수정 1건 │ 변경 없음 1건" in draft.summary
    assert "🔴 변경 없는 안내" in draft.summary
    assert "문서 유형: 변경 없음" in draft.summary
    assert "조건부 참여를 권장합니다. 기회 점수는 82점입니다." in draft.summary
    assert "접수 마감: 2026년 9월 30일" in draft.summary
    assert "우선 조치" in draft.summary
    assert "사업개발팀: 2026년 9월 5일까지 신청기업 자격을 확인합니다" in draft.summary
    assert "📎 원문\nhttps://example.go.kr/1" in draft.summary
    assert len(draft.title + "\n\n" + draft.summary) <= 4_096

def test_report_summary_stays_within_storage_limit() -> None:
    request = report_request()
    expanded = request.model_copy(
        update={
            "documents": [
                document.model_copy(update={"summary": "긴 요약 " * 5_000})
                for document in request.documents
            ]
        }
    )

    draft = TemplateReportGenerator().generate(expanded)

    assert len(draft.summary) <= 20_000
