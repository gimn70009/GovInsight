import re
from collections import Counter
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlsplit

from app.domains.analysis.schemas.result import DocumentImportance
from app.domains.report.facts import submission_facts
from app.domains.report.schemas.request import ReportDocumentRequest, ReportJobRequest
from app.domains.report.schemas.result import ReportDraft
from app.domains.report.submission_documents import submission_documents

_MAX_DISPLAY_UNITS = 3_900  # Reserve room for the title; Java/Telegram use UTF-16 offsets.
_MAX_STORAGE_UNITS = 20_000
_LINK = re.compile(r"\[([^\[\]\r\n]+)\]\((https?://[^\s()<>\"]+)\)")
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
_DOCUMENT_TYPE_LABEL = {
    "GENERAL_NOTICE": "일반 공지",
    "BUSINESS_NOTICE": "사업 공고",
    "PROPOSAL_REQUEST": "제안 요청",
    "REVIEW_REQUIRED": "유형 확인 필요",
}


class TemplateReportGenerator:
    def generate(self, request: ReportJobRequest) -> ReportDraft:
        documents = sorted(
            request.documents,
            key=lambda d: (-(d.opportunity_score or 0), _IMPORTANCE_ORDER[d.importance]),
        )
        report_date = request.requested_at or datetime.now(timezone(timedelta(hours=9)))
        return ReportDraft(
            title=f"[공공기관 모니터링] {report_date.month}월 {report_date.day}일 보고서",
            summary=_report_summary(documents),
        )


def display_units(value: str) -> int:
    return _utf16_units(_LINK.sub(lambda match: match[1], value))


def _utf16_units(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def _fits(value: str) -> bool:
    return display_units(value) <= _MAX_DISPLAY_UNITS and _utf16_units(value) <= _MAX_STORAGE_UNITS


def _report_summary(documents: list[ReportDocumentRequest]) -> str:
    counts = Counter(d.change_type for d in documents)
    header = (
        f"신규 {counts['NEW_DOCUMENT']}건 │ 수정 {counts['UPDATED_DOCUMENT']}건 │ "
        f"변경 없음 {counts['UNCHANGED_DOCUMENT']}건"
    )
    separator = "\n\n────────────────\n\n"
    detailed = separator.join([header, *[_document_block(d) for d in documents]])
    if _fits(detailed):
        return detailed
    # Do not cut a URL or strip out the action fields to make a message fit.
    blocks = [header]
    for index, document in enumerate(documents):
        block = _document_block(document, compact=True)
        remaining = len(documents) - index - 1
        tail = (
            separator + f"그 외 {remaining}건은 보고서 발송 상세의 게시글 보기에서 확인하세요."
            if remaining
            else ""
        )
        candidate = separator.join([*blocks, block])
        if not _fits(candidate + tail):
            blocks.append(
                f"그 외 {len(documents) - index}건은 보고서 발송 상세의 게시글 보기에서 확인하세요."
            )
            break
        blocks.append(block)
    return separator.join(blocks)


def _document_block(document: ReportDocumentRequest, compact: bool = False) -> str:
    facts = submission_facts(document)
    kind = (
        _DOCUMENT_TYPE_LABEL.get(document.proposal.document_type, "유형 확인 필요")
        if document.proposal
        else "유형 확인 필요"
    )
    score = (
        f"{document.opportunity_score}점" if document.opportunity_score is not None else "미산정"
    )
    change = _CHANGE_TYPE_LABEL.get(document.change_type, "상태 확인 필요")
    lines = [
        f"▸ {_shorten(document.title, 120 if compact else 180)}",
        f"기관: {_text(document.organization_name)}",
        f"문서 유형: {kind} │ {change} │ 기회점수: {score}",
        "",
        f"요약: {_brief_summary(document, 110 if compact else 180)}",
        "",
        f"• 제출 주체·대상: {_field(facts.applicant, compact)}",
        f"• 제출·의견 기한: {_field(facts.deadline, compact)}",
        f"• 제출처·방법: {_field(facts.destination, compact)}",
        f"• 문의 담당: {_field(facts.contact, compact)}",
        "",
        "제출 준비 서류 ↓",
    ]
    required = submission_documents(document)
    shown = 0
    used = 0
    seen = set()
    entries = []
    for item in required:
        form = item.form
        url = _safe_url(form.url) if form and form.url else None
        label = _text(item.title)
        # A member has no public URL of its own: identify the archive download honestly.
        if form and form.archive and url:
            label += " (ZIP)"
        key = (label, url)
        if key in seen:
            continue
        seen.add(key)
        entries.append(f"• [{label}]({url})" if url else f"• {label}")
    for entry in entries[: 3 if compact else 6]:
        if used + _utf16_units(entry) > (2_500 if compact else 6_000):
            break
        lines.append(entry)
        used += _utf16_units(entry)
        shown += 1
    if shown < len(entries):
        lines.append(f"추가 제출서류 {len(entries) - shown}종: 전체 목록은 원문 확인")
    if not entries:
        lines.append("확인된 제출 서류가 없습니다. 원문을 확인하세요.")
    original = _safe_url(document.original_url)
    lines.extend(
        [
            "",
            f"원문: [게시글 보기]({original})" if original else "원문: 모니터링 상세 화면에서 확인",
        ]
    )
    return "\n".join(lines)


def _brief_summary(document: ReportDocumentRequest, limit: int) -> str:
    value = document.comparison_summary.purpose if document.comparison_summary else None
    if not value or "확인하지 못" in value:
        value = document.summary
    value = _text(value)
    sentences = re.split(r"(?<=[다요][.])\s+|(?<=[!?])\s+", value)
    selected = []
    for sentence in sentences[:2]:
        if len(" ".join([*selected, sentence])) > limit:
            break
        selected.append(sentence)
    return " ".join(selected) if selected else _shorten(value, limit)


def _field(value: str, compact: bool) -> str:
    value = _text(value)
    # Truncating a deadline/condition could turn a qualification into an instruction.
    return value if len(value) <= (160 if compact else 300) else "상세 조건은 원문 확인"


def _text(value: str) -> str:
    return " ".join(value.split()).replace("[", "［").replace("]", "］")


def _shorten(value: str, max_chars: int) -> str:
    normalized = _text(value)
    if len(normalized) <= max_chars:
        return normalized
    return normalized[: max_chars - 1].rstrip() + "…"


def _safe_url(value: str) -> str | None:
    try:
        parsed = urlsplit(value)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username
            or parsed.password
        ):
            return None
        if any(ord(char) < 32 for char in value) or "\\" in value:
            return None
        return quote(value, safe="/:?#[]@!$&'*+,;=%~-._")
    except ValueError:
        return None
