"""Report-only source excerpts. No inferred assignee or contact-to-submission mapping."""

import re
from dataclasses import dataclass
from datetime import date

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
    applicant = _find(
        lines, r"(?:신청|지원|제출|참여)\s*(?:대상|주체|자격|기관)\s*[:：]", applicant=True
    )
    deadline = _find_deadline(
        lines,
        r"(?:접수|신청|제출|의견\s*제출)\s*(?:기간|기한|마감|일시)\s*[:：]",
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
        applicant = applicant or _usable_applicant(comparison.eligibility)
        deadline = deadline or _usable_deadline(comparison.application_deadline)
    preparation = document.proposal.preparation if document.proposal else None
    if preparation:
        deadline = deadline or _usable_deadline(preparation.application_deadline)
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


_LABEL_TEXT = (
    r"(?:(?:신청|지원|제출|참여)\s*(?:대상|주체|자격|기관|서류|처|방법|기간|기한|마감|일시)"
    r"|접수\s*(?:방법|방식|처|장소|경로|기간|기한|마감|일시)"
    r"|의견\s*제출\s*(?:기간|기한)|문의(?:처|사항)?|담당(?:자|부서))"
)
_SOURCE_LABEL = re.compile(r"^" + _LABEL_TEXT + r"\s*[:：]?$")
_ANY_LABEL = re.compile(_LABEL_TEXT + r"\s*[:：]")
_SECTION_BOUNDARY = re.compile(
    r"^(?:[가-하0-9]+[.)]\s*)?(?:접수\s*및\s*문의(?:처)?|(?:신청|접수|제출|작성).*유의사항"
    r"|유의사항|평가\s*(?:방법|절차|기준)|선정\s*(?:절차|방법)|기타\s*사항)(?:\s*[:：]?$|\s+/)|^\[파일:"
)
_BULLET = re.compile(r"^[\s○□ㅇ•※*\-▷▶▸‣◆◇■●◎]+")
_TABLE_HEADERS = {
    "역할",
    "구분",
    "대상",
    "기관명",
    "기관",
    "비고",
    "내용",
    "신청자격",
    "성명",
    "소속",
    "담당자",
    "작성요령",
    "작성예시",
    "예시",
    "신청기관",
    "업체명",
}


def _placeholder(value: str) -> bool:
    compact = re.sub(r"[\s|:：_\-()（）]+", "", value)
    return (
        not compact
        or compact in _TABLE_HEADERS
        or bool(re.fullmatch(r"[○ㅇO□.·…]+", compact))
        or bool(re.match(r"(?:역\s*할|구\s*분)\s*[|\t]", value))
    )


def _source_lines(sources: list[str]) -> list[str]:
    result = []
    for source in sources:
        lines = [
            _BULLET.sub("", line).strip()
            for line in re.split(r"\r\n|[\r\n]|(?<=[다요]\.)[ \t]+", source)
        ]
        index = 0
        while index < len(lines):
            line = lines[index]
            match = _ANY_LABEL.search(line)
            standalone = _SOURCE_LABEL.fullmatch(line)
            if match or standalone:
                label_end = match.end() if match else len(line)
                value = line[label_end:].strip()
                label = line[:label_end].rstrip(":： ")
                values = [value] if value else []
                next_index = index + 1
                blocked = _placeholder(value) if value else False
                while next_index < len(lines):
                    following = lines[next_index]
                    if (
                        not following
                        or _ANY_LABEL.search(following)
                        or _SOURCE_LABEL.fullmatch(following)
                        or _SECTION_BOUNDARY.search(following)
                        or re.match(
                            r"^(?:[가-하][.)]|[0-9]+[.)])\s*(?:사업개요|평가|선정|유의|기타)",
                            following,
                        )
                    ):
                        break
                    if _placeholder(following):
                        blocked = True
                        break
                    values.append(following)
                    next_index += 1
                if not blocked and values:
                    result.append(label + ": " + " / ".join(values))
                index = max(index + 1, next_index)
            elif line:
                result.append(line)
                index += 1
            else:
                index += 1
    return result


def _find(
    lines: list[str], pattern: str, required: str | None = None, *, applicant: bool = False
) -> str | None:
    matches = []
    for line in lines:
        normalized = " ".join(line.split())
        match = re.search(pattern, normalized)
        if not 4 <= len(normalized) <= 280 or not match:
            continue
        if required and not re.search(required, normalized):
            continue
        value = normalized[match.end() :].strip()
        if (match.group().endswith((":", "：")) and _placeholder(value)) or (
            applicant and not _usable_applicant(value)
        ):
            continue
        if match.start() == 0 and match.group().endswith((":", "：")):
            normalized = value
        if normalized not in matches:
            matches.append(normalized)
    if not matches:
        return None
    result = " / ".join(matches[:2])
    if len(matches) > 2:
        result += f" 외 {len(matches) - 2}개 안내 (원문 확인)"
    return result


def _usable(value: str | None) -> str | None:
    if not value or "확인하지 못" in value or "확인 필요" in value or _placeholder(value):
        return None
    normalized = " ".join(value.split())
    return normalized if len(normalized) <= 300 else None


def _usable_applicant(value: str | None) -> str | None:
    candidate = _usable(value)
    if not candidate or re.match(r"^(?:예시|예\s*[:：]|작성\s*(?:요령|예))", candidate):
        return None
    return (
        candidate
        if re.search(
            r"기업|기관|단체|법인|지자체|지방자치|시[ㆍ·]도|시장|군수|구청장|대학|연구|주민|국민|누구나|개인|사업자|조합|컨소시엄|공동|비영리",
            candidate,
        )
        else None
    )


_DATE = re.compile(
    r"(?<!\d)(?:(?P<year>20\d{2}|\d{2})\s*(?:년\s*|[./-]\s*))?"
    r"(?P<month>1[0-2]|0?[1-9])\s*(?:월\s*|[./-]\s*)"
    r"(?P<day>3[01]|[12]\d|0?[1-9])(?:일|\.)?(?!\d)"
)


def _usable_deadline(value: str | None) -> str | None:
    candidate = _usable(value)
    if not candidate:
        return None
    # Drop section headings joined by the old line flattener, not date ranges.
    segments = candidate.split(" / ")
    for index, segment in enumerate(segments):
        if _SECTION_BOUNDARY.search(segment):
            segments = segments[:index]
            break
    candidate = " / ".join(segments)
    dates = list(_DATE.finditer(candidate))
    if not dates:
        return (
            candidate
            if re.fullmatch(r"(?:상시|수시)(?:\s*접수)?|(?:별도|추후)\s*(?:안내|공지)", candidate)
            else None
        )
    for match in dates:
        year = int(match["year"]) if match["year"] else 2000
        if year < 100:
            year += 2000
        try:
            date(year, int(match["month"]), int(match["day"]))
        except ValueError:
            return None
    # A damaged range cannot safely be presented as its one readable endpoint.
    if re.search(r"[~∼～]|부터", candidate) and len(dates) < 2:
        return None
    return candidate


def _find_deadline(lines: list[str], pattern: str) -> str | None:
    # Validate each candidate before selection, so an invalid first line cannot mask a good one.
    values = []
    for line in lines:
        value = _usable_deadline(_find([line], pattern))
        if value and value not in values:
            values.append(value)
    return " / ".join(values[:2]) if values else None
