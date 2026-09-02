from collections import Counter
from datetime import datetime, timedelta, timezone

from app.domains.analysis.schemas.result import (
    DocumentImportance,
    Eligibility,
    StrategyDecision,
)
from app.domains.report.schemas.request import ReportDocumentRequest, ReportJobRequest
from app.domains.report.schemas.result import ReportDraft

_MAX_REPORT_CHARS = 3_900
_IMPORTANCE_ORDER = {
    DocumentImportance.HIGH: 0,
    DocumentImportance.NORMAL: 1,
    DocumentImportance.LOW: 2,
}
_CHANGE_TYPE_LABEL = {
    "NEW_DOCUMENT": "신규",
    "UPDATED_DOCUMENT": "수정",
    "UNCHANGED_DOCUMENT": "변경 없음",
}
_DECISION_LABEL = {
    StrategyDecision.GO: "참여를 권장합니다.",
    StrategyDecision.CONDITIONAL_GO: "조건부 참여를 권장합니다.",
    StrategyDecision.HOLD: "추가 검토가 필요합니다.",
    StrategyDecision.NO_GO: "현재 조건에서는 참여를 권장하지 않습니다.",
}


class TemplateReportGenerator:
    def generate(self, request: ReportJobRequest) -> ReportDraft:
        documents = sorted(
            request.documents,
            key=lambda document: (
                -(document.opportunity_score or 0),
                _IMPORTANCE_ORDER[document.importance],
            ),
        )
        report_date = request.requested_at or datetime.now(timezone(timedelta(hours=9)))
        title = f"[공공기관 모니터링] {report_date.month}월 {report_date.day}일 보고서"
        summary = _report_summary(documents)
        return ReportDraft(title=title, summary=summary)


def _report_summary(documents: list[ReportDocumentRequest]) -> str:
    counts = Counter(document.change_type for document in documents)
    header = (
        f"신규 {counts['NEW_DOCUMENT']}건 │ "
        f"수정 {counts['UPDATED_DOCUMENT']}건 │ "
        f"변경 없음 {counts['UNCHANGED_DOCUMENT']}건"
    )
    detailed_blocks = [_document_block(document) for document in documents]
    detailed = "\n\n\n".join([header, *detailed_blocks])
    if len(detailed) <= _MAX_REPORT_CHARS:
        return detailed

    compact_blocks = [_compact_document_block(document) for document in documents]
    lines = [header]
    omitted = 0
    for index, block in enumerate(compact_blocks):
        candidate = "\n\n\n".join([*lines, block])
        if len(candidate) > _MAX_REPORT_CHARS - 80:
            omitted = len(compact_blocks) - index
            break
        lines.append(block)
    if omitted:
        lines.append(f"그 외 {omitted}건은 공공기관 모니터링 상세 화면에서 확인할 수 있습니다.")
    return "\n\n\n".join(lines)


def _document_block(document: ReportDocumentRequest) -> str:
    lines = [
        f"🔴 {_shorten(document.title, 180)}",
        "",
        f"문서 유형: {_CHANGE_TYPE_LABEL.get(document.change_type, document.change_type)}",
        f"기관: {document.organization_name}",
    ]
    deadline = _application_deadline(document)
    if deadline:
        lines.append(f"접수 마감: {_format_date(deadline)}")

    lines.extend(["", _decision_sentence(document), "", _shorten(document.summary, 420)])
    if document.reason:
        reason = _shorten(document.reason, 260)
        if reason not in document.summary:
            lines.extend(["", reason])

    actions = _priority_actions(document)
    if actions:
        lines.extend(["", "우선 조치"])
        lines.extend(f"• {action}" for action in actions[:3])

    lines.extend(["", "📎 원문", document.original_url])
    return "\n".join(lines)


def _compact_document_block(document: ReportDocumentRequest) -> str:
    return "\n".join(
        [
            f"🔴 {_shorten(document.title, 140)}",
            f"문서 유형: {_CHANGE_TYPE_LABEL.get(document.change_type, document.change_type)}",
            f"기관: {document.organization_name}",
            _decision_sentence(document),
            "📎 원문",
            document.original_url,
        ]
    )


def _decision_sentence(document: ReportDocumentRequest) -> str:
    decision = None
    if document.proposal and document.proposal.preparation:
        decision = _DECISION_LABEL.get(document.proposal.preparation.strategy.decision)
    if decision is None:
        decision = {
            Eligibility.ELIGIBLE.value: "참여를 검토할 수 있습니다.",
            Eligibility.REVIEW_REQUIRED.value: "참여 조건을 추가로 확인해야 합니다.",
            Eligibility.INELIGIBLE.value: "현재 조건에서는 참여하기 어렵습니다.",
        }.get(document.eligibility, "공고 내용을 확인해 대응 여부를 결정해야 합니다.")
    if document.opportunity_score is not None:
        return f"{decision} 기회 점수는 {document.opportunity_score}점입니다."
    return decision


def _application_deadline(document: ReportDocumentRequest) -> str | None:
    if document.proposal and document.proposal.preparation:
        return document.proposal.preparation.application_deadline
    return None


def _priority_actions(document: ReportDocumentRequest) -> list[str]:
    if not document.proposal or not document.proposal.preparation:
        return []
    actions = []
    for gap in document.proposal.preparation.strategy.critical_gaps:
        timing = (
            f"{_format_date(gap.target_date)}까지"
            if gap.target_date
            else gap.target_timing.rstrip(". ")
        )
        action = gap.next_action.rstrip(". ")
        actions.append(f"{gap.owner}: {timing} {action}")
    return actions


def _format_date(value: str) -> str:
    try:
        parsed = datetime.strptime(value, "%Y-%m-%d")
        return f"{parsed.year}년 {parsed.month}월 {parsed.day}일"
    except ValueError:
        return value


def _shorten(value: str, max_chars: int) -> str:
    normalized = " ".join(value.split())
    if len(normalized) <= max_chars:
        return normalized
    return normalized[: max_chars - 1].rstrip() + "…"