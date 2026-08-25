from app.domains.report.schemas.request import ReportJobRequest
from app.domains.report.template import TemplateReportGenerator


def report_request() -> ReportJobRequest:
    return ReportJobRequest.model_validate(
        {
            "runId": 10,
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
    draft = TemplateReportGenerator().generate(report_request())

    assert draft.title == "산업통상부(사업공고) 외 1개 게시판 모니터링 보고서"
    assert "모니터링 소스 2개에서 문서 3건" in draft.summary
    assert "중요 1건 · 보통 1건 · 낮음 1건" in draft.summary
    assert "중요 신규 지원사업 (신규)" in draft.summary
    assert "수정된 지원사업 (수정)" in draft.summary
    assert "변경 없는 안내 (변경 없음)" in draft.summary
    assert draft.summary.index("중요 신규 지원사업") < draft.summary.index("변경 없는 안내")


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
