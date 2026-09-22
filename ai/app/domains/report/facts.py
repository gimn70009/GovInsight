"""Report-only source excerpts. No inferred assignee or contact-to-submission mapping."""

import re
from dataclasses import dataclass

from app.domains.report.schemas.request import ReportDocumentRequest

_UNKNOWN = "원문 확인 필요"


@dataclass(frozen=True)
class SubmissionFacts:
    applicant: str
    deadline: str
    destination: str
    documents: str
    contact: str


def submission_facts(document: ReportDocumentRequest) -> SubmissionFacts:
    sources = [document.content_text or ""]
    sources.extend(a.extracted_text or "" for a in document.attachments)
    lines = _source_lines(sources)
    # Only preserve short, complete source passages. Long sections stay in the original.
    applicant = _find(lines, r"(?:신청|지원|제출|참여)\s*(?:대상|주체|자격|기관)\s*[:：]")
    deadline = _find(
        lines,
        r"(?:접수|신청|제출|의견\s*제출)\s*(?:기간|기한|마감|일시)\s*[:：]",
        r"\d|상시|수시|별도|추후",
    )
    destination = _find(lines, r"(?:접수|신청|제출)\s*(?:방법|방식|처|장소|경로)\s*[:：]")
    if not destination:
        # An inquiry address alone must never become a submission address.
        destination = _find(
            lines,
            r"(?:제출|접수)(?:해야|하여야|합니다|한다|\s*가능|\s*바랍니다)|(?:로|으로)\s*(?:제출|접수)",
            r"이메일|전자\s*우편|온라인|방문|우편|https?://",
        )
    contact = _find(
        lines, r"(?:문의(?:처|사항)?|담당(?:자|부서))\s*[:：]", r"@|\d{2,}|팀|과|부|센터"
    )
    documents = _find(lines, r"(?:제출|신청|구비)\s*서류\s*[:：]")
    comparison = document.comparison_summary
    if comparison:
        applicant = applicant or _usable(comparison.eligibility)
        deadline = deadline or _usable(comparison.application_deadline)
    preparation = document.proposal.preparation if document.proposal else None
    if preparation:
        deadline = deadline or preparation.application_deadline
        if not documents:
            official = [
                f"{item.title}"
                + (f" ({item.applies_to})" if item.requirement_level == "CONDITIONAL" else "")
                for item in preparation.submission_documents
                if item.stage == "APPLICATION"
                and item.source
                and item.source.origin in {"NOTICE_BODY", "ATTACHMENT"}
                and item.requirement_level in {"MANDATORY", "CONDITIONAL"}
            ]
            if official:
                documents = " · ".join(official[:3])
                if len(official) > 3:
                    documents += f" 외 {len(official) - 3}종 (전체 목록은 원문 확인)"
    return SubmissionFacts(
        applicant or _UNKNOWN,
        deadline or _UNKNOWN,
        destination or _UNKNOWN,
        documents or _UNKNOWN,
        contact or _UNKNOWN,
    )


_SOURCE_LABEL = re.compile(
    r"^(?:(?:신청|지원|제출|참여)\s*(?:대상|주체|자격|기관|서류|처|방법|기간|기한|마감|일시)"
    r"|접수\s*(?:방법|방식|처|장소|경로|기간|기한|마감|일시)"
    r"|문의(?:처|사항)?|담당(?:자|부서))\s*[:：]?$"
)


def _source_lines(sources: list[str]) -> list[str]:
    result = []
    for source in sources:
        lines = [
            re.sub(r"^[\s○□ㅇ•※*\-▷▶▸‣◆◇■●◎]+", "", line).strip()
            for line in re.split(r"[\r\n]+|(?<=[다요])\.\s+", source)
            if line.strip()
        ]
        for index, line in enumerate(lines):
            # Some extracted tables put the label and its value on separate lines.
            if _SOURCE_LABEL.fullmatch(line) and index + 1 < len(lines):
                value = lines[index + 1]
                if not _SOURCE_LABEL.fullmatch(value) and not re.search(
                    r"(?:제출|접수|문의|담당|신청).*[:：]", value
                ):
                    line = line.rstrip(":： ") + ": " + value
            result.append(line)
    return result


def _find(lines: list[str], pattern: str, required: str | None = None) -> str | None:
    matches = []
    for line in lines:
        normalized = " ".join(line.split())
        if not 4 <= len(normalized) <= 240 or not re.search(pattern, normalized):
            continue
        if required and not re.search(required, normalized):
            continue
        match = re.search(pattern, normalized)
        if match and match.start() == 0 and match.group().endswith((":", "：")):
            normalized = normalized[match.end() :].strip()
            if len(normalized) < 2:
                continue
        if normalized not in matches:
            matches.append(normalized)
    if not matches:
        return None
    result = " / ".join(matches[:2])
    if len(matches) > 2:
        result += f" 외 {len(matches) - 2}개 안내 (원문 확인)"
    return result


def _usable(value: str | None) -> str | None:
    if not value or "확인하지 못" in value or "확인 필요" in value:
        return None
    normalized = " ".join(value.split())
    return normalized if len(normalized) <= 300 else None
