import re
from collections import Counter
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlsplit

from app.domains.analysis.schemas.result import DocumentImportance
from app.domains.report.brief import SubmissionBrief, source_brief_facts
from app.domains.report.opportunity import source_opportunity_facts
from app.domains.report.presentation import (
    foreign_prose,
    informational_notice,
    source_check_message,
    submission_display_lines,
    unreadable_table,
)
from app.domains.report.quality import schedule_consistency_issues
from app.domains.report.schedules import schedule_fields
from app.domains.report.schemas.request import ReportDocumentRequest, ReportJobRequest
from app.domains.report.schemas.result import ReportDraft
from app.domains.report.submission_documents import (
    SubmissionDocument,
    display_submission_documents,
    fallback_submission_documents,
)
from app.domains.report.summary import purpose_summary

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
    def generate(
        self, request: ReportJobRequest, *, briefs: dict[int, SubmissionBrief] | None = None
    ) -> ReportDraft:
        documents = sorted(
            request.documents,
            key=lambda d: (-(d.opportunity_score or 0), _IMPORTANCE_ORDER[d.importance]),
        )
        report_date = request.requested_at or datetime.now(timezone(timedelta(hours=9)))
        return ReportDraft(
            title=f"[공공기관 모니터링] {report_date.month}월 {report_date.day}일 보고서",
            summary=_report_summary(documents, briefs or {}),
        )


def display_units(value: str) -> int:
    return _utf16_units(_LINK.sub(lambda match: match[1], value))


def _utf16_units(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def _fits(value: str) -> bool:
    return _utf16_units(value) <= _MAX_STORAGE_UNITS


def _report_summary(
    documents: list[ReportDocumentRequest], briefs: dict[int, SubmissionBrief]
) -> str:
    counts = Counter(d.change_type for d in documents)
    header = (
        f"신규 {counts['NEW_DOCUMENT']}건 │ 수정 {counts['UPDATED_DOCUMENT']}건 │ "
        f"변경 없음 {counts['UNCHANGED_DOCUMENT']}건"
    )
    separator = "\n\n────────────────\n\n"
    detailed = separator.join(
        [header, *[_document_block(d, brief=briefs.get(d.version_id)) for d in documents]]
    )
    if _fits(detailed):
        return detailed
    # Do not cut a URL or strip out the action fields to make a message fit.
    blocks = [header]
    for index, document in enumerate(documents):
        block = _document_block(document, compact=True, brief=briefs.get(document.version_id))
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


def _document_block(
    document: ReportDocumentRequest, compact: bool = False, brief: SubmissionBrief | None = None
) -> str:
    facts = brief.facts if brief else source_brief_facts(document)
    opportunity = brief.opportunity if brief else source_opportunity_facts(document)
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
        f"공고 성격: {opportunity.notice_kind}",
        *([
            f"사업 유형: {opportunity.business_type or '확인 필요'}",
            f"사업 방식: {opportunity.business_method or '확인 필요'}",
        ] if opportunity.notice_kind == "사업 기회" else []),
        "",
        f"요약: {_summary(document, facts, opportunity)}",
        "",
    ]
    schedule_issues = schedule_consistency_issues(document, facts.deadline)
    if "deadline:summary_conflict" in schedule_issues:
        lines.extend([
            "안내: 요약과 제출 안내의 일정이 다릅니다. "
            "원문에서 확인한 아래 제출 기한을 우선 확인해 주세요.",
            "",
        ])
    elif "deadline:summary_without_verified_schedule" in schedule_issues:
        lines.extend([
            "안내: 요약에 적힌 제출 기한은 원문 근거를 확인하지 못했습니다. "
            "신청 전에 원문을 확인해 주세요.",
            "",
        ])
    missing = []
    informational = (opportunity.notice_kind == "일반 공지" and not re.search(
        r"이의신청|의견.{0,8}제출|신청|접수|제출", document.content_text or "",
    )) or informational_notice(
        document.title, document.content_text or "",
    )
    schedules = schedule_fields(facts.deadline)
    for label, value in [
        ("금액·규모", opportunity.amount or ""),
        ("참여 역할", opportunity.participation_role or ""),
        ("제출 주체·대상", facts.applicant),
        ("공고일", "\n".join(schedules["공고일"])),
        ("제출·의견 기한", "\n".join(schedules["제출·의견 기한"])),
        ("관련 일정", "\n".join(schedules["관련 일정"])),
        ("제출처·방법", facts.destination),
        ("문의 담당", facts.contact),
    ]:
        display_lines = (
            value.splitlines() if label == "금액·규모" else submission_display_lines(value, label)
        )
        items = [
            _text(item) if label in {"금액·규모", "참여 역할"} else _field(item, compact)
            for item in display_lines if item.strip()
        ]
        items = [item for item in items if not foreign_prose(item)]
        valid = [item for item in items if item not in {"원문 확인 필요", "상세 조건은 원문 확인"}]
        if not valid:
            if label not in {"공고일", "관련 일정", "금액·규모", "참여 역할"}:
                missing.append(label)
            if (
                label == "제출·의견 기한" and not informational
                or opportunity.notice_kind == "사업 기회"
                and label in {"금액·규모", "제출 주체·대상"}
            ):
                lines.append(f"• {label}: 원문 확인 필요")
        else:
            lines.append(f"• {label}: {valid[0]}")
            lines.extend(f"  ↳ {item}" for item in valid[1:])
    if brief is None:
        required, uses_saved_checklist = fallback_submission_documents(document)
    else:
        required, uses_saved_checklist = brief.documents, brief.uses_saved_checklist
    shown = 0
    used = 0
    entries = _submission_entries(required)
    if entries:
        lines.extend(["", "제출 준비 서류 ↓"])
    for entry in entries[: 3 if compact else 6]:
        if used + _utf16_units(entry) > (2_500 if compact else 6_000):
            break
        lines.append(entry)
        used += _utf16_units(entry)
        shown += 1
    if shown < len(entries):
        location = (
            "사업 제안 체크리스트" if uses_saved_checklist else "원문"
        )
        lines.append(f"추가 제출서류 {len(entries) - shown}종: {location}에서 확인")
    if brief:
        if not entries:
            missing.append("제출 서류")
        if brief.note:
            missing.append(brief.note)
    elif not required and (not document.proposal or not document.proposal.preparation):
        missing.append("제출 서류 체크리스트 미작성")
    if not uses_saved_checklist and any(item.requirement_level is not None for item in required):
        if "원문 표에서 확인한 서류만 표시" not in missing:
            missing.append("원문 표에서 확인한 서류만 표시")
    if missing and not informational:
        labels = {
            "제출 주체·대상": "신청 자격",
            "제출·의견 기한": "제출 기한",
            "제출처·방법": "접수 방법",
            "문의 담당": "문의처",
            "제출 서류": "준비 서류",
            "제출 서류 체크리스트 미작성": "준비 서류",
            "제출 대상·조건 확인 필요": "서류별 제출 대상·조건",
            "원문 표에서 확인한 서류만 표시": "전체 제출서류 목록",
        }
        pending = [labels[item] for item in missing if item in labels]
        if pending:
            subject = "·".join(pending) + " 항목" if len(pending) <= 2 else "일부 접수 정보"
            if len(pending) > 2 and "전체 제출서류 목록" in pending:
                subject = "전체 제출서류 목록 등 일부 접수 정보"
            lines.extend(
                [
                    "",
                    "안내: "
                    + subject
                    + ("은" if len(pending) <= 2 else "는")
                    + " 원문에서 확인해 주세요.",
                ]
            )
        elif brief and brief.note:
            lines.extend(["", "안내: 일부 안내를 정리하지 못했습니다. 원문을 확인해 주세요."])
    original = _safe_url(document.original_url)
    lines.extend(
        [
            "",
            f"원문: [게시글 보기]({original})" if original else "원문: 모니터링 상세 화면에서 확인",
        ]
    )
    return "\n".join(lines)


def _submission_entries(required: list[SubmissionDocument]) -> list[str]:
    """One download per URL, without discarding distinct submission requirements."""
    groups: list[tuple[str | None, list[SubmissionDocument]]] = []
    positions: dict[str, int] = {}
    for item in display_submission_documents(required):
        url = _safe_url(item.form.url) if item.form and item.form.url else None
        # Fragments select a page inside the same file; query parameters identify files.
        key = url.split("#", 1)[0] if url else None
        if key and key in positions:
            groups[positions[key]][1].append(item)
        else:
            if key:
                positions[key] = len(groups)
            groups.append((url, [item]))
    entries = []
    for url, items in groups:
        labels = []
        for item in items:
            label = _text(item.title)
            if item.requirement_level == "OPTIONAL" or item.condition in {"선택", "선택 제출"}:
                label += " (선택 제출)"
            if label not in labels:
                labels.append(label)
        if len(items) > 1:
            filenames = {item.form.archive or item.form.name for item in items}
            filename = next(iter(filenames)) if len(filenames) == 1 else None
            label = _text(filename or "통합 서류 파일")
            entries.append(f"• [{label}]({url})\n  ↳ 관련 서류: " + " · ".join(labels))
        else:
            item = items[0]
            label = _text(item.title)
            if item.form and item.form.archive and url:
                label += " (ZIP)"
            entry = f"• [{label}]({url})" if url else f"• {label}"
            if labels[0] != _text(item.title):
                entry += " (선택 제출)"
            entries.append(entry)
    return entries


def _summary(document: ReportDocumentRequest, facts, opportunity) -> str:
    # Only report prose changes; the saved analysis and source evidence stay intact.
    if unreadable_table(document.summary or ""):
        return source_check_message("사업 요약")
    summary = _text(purpose_summary(document, facts, opportunity))
    return summary or "분석 요약을 확인하지 못했습니다."


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
