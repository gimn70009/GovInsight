from collections import Counter

from app.domains.analysis.schemas.result import DocumentImportance
from app.domains.report.schemas.request import ReportDocumentRequest, ReportJobRequest
from app.domains.report.schemas.result import ReportDraft

_MAX_REPORT_CHARS = 20_000
_IMPORTANCE_ORDER = {
    DocumentImportance.HIGH: 0,
    DocumentImportance.NORMAL: 1,
    DocumentImportance.LOW: 2,
}
_IMPORTANCE_LABEL = {
    DocumentImportance.HIGH: "중요",
    DocumentImportance.NORMAL: "보통",
    DocumentImportance.LOW: "낮음",
}
_CHANGE_TYPE_LABEL = {
    "NEW_DOCUMENT": "신규",
    "UPDATED_DOCUMENT": "수정",
    "UNCHANGED_DOCUMENT": "변경 없음",
}


class TemplateReportGenerator:
    def generate(self, request: ReportJobRequest) -> ReportDraft:
        documents = sorted(
            request.documents,
            key=lambda document: _IMPORTANCE_ORDER[document.importance],
        )
        groups = _group_documents(documents)
        title = _report_title(groups)
        summary = _report_summary(request, groups)
        return ReportDraft(title=title, summary=summary)


def _group_documents(
    documents: list[ReportDocumentRequest],
) -> list[tuple[tuple[str, str], list[ReportDocumentRequest]]]:
    groups: dict[tuple[str, str], list[ReportDocumentRequest]] = {}
    for document in documents:
        key = (document.organization_name, document.board_name)
        groups.setdefault(key, []).append(document)
    return list(groups.items())


def _report_title(
    groups: list[tuple[tuple[str, str], list[ReportDocumentRequest]]],
) -> str:
    organization, board = groups[0][0]
    if len(groups) == 1:
        return f"{organization}({board}) 모니터링 보고서"
    return f"{organization}({board}) 외 {len(groups) - 1}개 게시판 모니터링 보고서"


def _report_summary(
    request: ReportJobRequest,
    groups: list[tuple[tuple[str, str], list[ReportDocumentRequest]]],
) -> str:
    counts = Counter(document.importance for document in request.documents)
    lines = [
        "모니터링 실행 요약",
        (
            f"모니터링 소스 {request.total_source_count}개에서 "
            f"문서 {request.detected_document_count}건을 확인했으며 "
            f"경고 {request.warning_count}건이 발생했습니다."
        ),
        (
            f"중요도: 중요 {counts[DocumentImportance.HIGH]}건 · "
            f"보통 {counts[DocumentImportance.NORMAL]}건 · "
            f"낮음 {counts[DocumentImportance.LOW]}건"
        ),
    ]

    document_number = 1
    for (organization, board), documents in groups:
        lines.extend(["", f"{organization} / {board}"])
        for document in documents:
            lines.extend(_document_lines(document_number, document))
            document_number += 1

    summary = "\n".join(lines)
    if len(summary) <= _MAX_REPORT_CHARS:
        return summary
    return summary[: _MAX_REPORT_CHARS - 3].rstrip() + "..."


def _document_lines(number: int, document: ReportDocumentRequest) -> list[str]:
    lines = [
        "",
        (
            f"{number}. {_shorten(document.title, 300)} "
            f"({_CHANGE_TYPE_LABEL.get(document.change_type, document.change_type)})"
        ),
        f"중요도: {_IMPORTANCE_LABEL[document.importance]}",
        f"요약: {_shorten(document.summary, 1_000)}",
    ]
    if document.key_points:
        key_points = " · ".join(_shorten(point, 300) for point in document.key_points[:5])
        lines.append(f"핵심: {key_points}")
    if document.reason:
        lines.append(f"판단 근거: {_shorten(document.reason, 500)}")
    lines.append(f"원문: {document.original_url}")
    return lines


def _shorten(value: str, max_chars: int) -> str:
    normalized = " ".join(value.split())
    if len(normalized) <= max_chars:
        return normalized
    return normalized[: max_chars - 1].rstrip() + "…"
