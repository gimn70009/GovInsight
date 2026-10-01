"""Korean presentation checks, separate from source extraction and evidence matching."""

import re
import unicodedata
from datetime import date

from app.domains.report.facts import _DATE

_MONTHS = (
    "January February March April May June July August September October November December".split()
)
_ENGLISH_DATE = re.compile(
    r"\b(" + "|".join(_MONTHS) + r")\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b", re.I
)

_LINK = re.compile(r"https?://[^\s<>]+|www\.[^\s<>]+|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}")


def foreign_prose(value: str) -> bool:
    text = _LINK.sub("", value)
    words = re.findall(r"[A-Za-z]{3,}", text)
    return len(words) >= 7 or (len(words) >= 4 and not re.search(r"[가-힣]", text))


def _anchors(value: str) -> set[str]:
    value = unicodedata.normalize("NFKC", value)
    anchors = {link.rstrip(".,;:)]}") for link in _LINK.findall(value)}
    value = _LINK.sub("", value)

    def date_key(match):
        year = match["year"]
        year = str(2000 + int(year)) if year and len(year) == 2 else year
        anchors.add(f"date:{year or ''}-{int(match['month'])}-{int(match['day'])}")
        return ""

    def english_date_key(match):
        month = next(i for i, name in enumerate(_MONTHS, 1) if name.lower() == match[1].lower())
        anchors.add(f"date:{match[3]}-{month}-{int(match[2])}")
        return ""

    value = _ENGLISH_DATE.sub(english_date_key, value)
    value = _DATE.sub(date_key, value)
    anchors.update(re.findall(r"\d+(?:[.,:/~-]\d+)*(?:\s*%)?", value))
    return anchors


def korean_display(raw: str, quote: str, display: str | None) -> str | None:
    if display is None:
        return None
    value = " ".join(display.split())
    if not value or len(value) > 150 or not re.search(r"[가-힣]", value) or foreign_prose(value):
        return None
    # Translation may change words, never invent or silently remove numeric/contact facts.
    if not _anchors(value) <= _anchors(quote) or not _anchors(raw) <= _anchors(value):
        return None

    def bounds(text):
        return set(re.findall(r"(?<=\d)\s*(?:%|억원|원|명|년|일)?\s*(이상|이하|미만|초과)", text))

    if not bounds(raw) <= bounds(value):
        return None
    return value


def readable_source(value: str, kind: str) -> str | None:
    if len(value) > 150 or foreign_prose(value):
        return None
    if kind == "applicant":
        # Isolated table cells lack the role needed to interpret eligibility.
        if re.fullmatch(r"중소[·ㆍ\s]*중견|제한\s*없음", value) or re.search(
            r"(?<![가-힣])자\s+(?:[가-힣]+\s+){0,2}격(?![가-힣])", value
        ):
            return None
    return value


def informational_notice(title: str, source: str) -> bool:
    # Only suppress missing application fields for explicit result announcements.
    result = re.search(r"(?:선정|평가|심사)\s*결과|최종\s*선정.*(?:안내|공고)", title)
    action = re.search(r"이의\s*신청|추가\s*제출|접수\s*(?:기간|기한)|제출\s*기한", source)
    return bool(result and not action)


_PAIRED_DEADLINE = re.compile(
    r"^(?P<announcement>(?:대회\s*)?공고(?:일자|일)?)\s*/\s*"
    r"(?P<closing>(?:접수|신청|제출)\s*마감(?:일자|일)?)\s*[:：]\s*(?P<dates>.+)$"
)
_DEADLINE_LABEL = re.compile(r"(?:[가-힣A-Za-z(]|\d+차)[^:\n]{0,40}[:：]\s*")


def _deadline_slashes(value: str) -> list[int]:
    protected = [m.span() for pattern in (_DATE, _LINK) for m in pattern.finditer(value)]
    return [
        m.start() for m in re.finditer("/", value)
        if not any(start <= m.start() < end for start, end in protected)
        and value[:m.start()].count("(") == value[:m.start()].count(")")
    ]


def _deadline_dates(value: str) -> str:
    links = [m.span() for m in _LINK.finditer(value)]

    def replace(match: re.Match[str]) -> str:
        if any(start <= match.start() < end for start, end in links):
            return match[0]
        year = int(match["year"]) if match["year"] else None
        if year is not None and year < 100:
            if year > 49:
                return match[0]
            year += 2000
        month, day = int(match["month"]), int(match["day"])
        try:
            date(year or 2000, month, day)
        except ValueError:
            return match[0]
        return (f"{year}년 " if year else "") + f"{month}월 {day}일"

    value = _DATE.sub(replace, value)
    value = re.sub(r"['‘’](?=20\d{2}년)", "", value)
    value = re.sub(r"일\s*\(\s*([월화수목금토일])\s*\)", r"일(\1)", value)
    return re.sub(r"(일(?:\([월화수목금토일]\))?)(?=\d{1,2}:)", r"\1 ", value)


def deadline_display_lines(value: str) -> list[str]:
    """Format verified dates without treating an announcement date as a range start."""
    chunks = []
    for line in value.splitlines():
        start = 0
        for index in _deadline_slashes(line):
            # Only a new labelled schedule starts a new block here. Keep paired
            # headings and their two date cells together for the mapping below.
            if _DATE.search(line[start:index]) and _DEADLINE_LABEL.match(line[index + 1:].strip()):
                chunks.append(line[start:index].strip())
                start = index + 1
        chunks.append(line[start:].strip())

    lines = []
    for chunk in chunks:
        paired = _PAIRED_DEADLINE.fullmatch(chunk)
        if paired:
            dates = paired["dates"]
            separators = _deadline_slashes(dates)
            if len(separators) == 1:
                left, right = dates[:separators[0]].strip(), dates[separators[0] + 1:].strip()
                if all(_DATE.match(item.lstrip("'‘’")) for item in (left, right)):
                    announcement = re.sub(r"공고(?:일자|일)?$", "공고일", paired["announcement"])
                    announcement = re.sub(r"대회\s*공고", "대회 공고", announcement)
                    closing = re.sub(r"\s*마감(?:일자|일)?$", " 마감", paired["closing"])
                    lines.extend([f"{announcement}: {left}", f"{closing}: {right}"])
                    continue
        # Preserve unlabelled dates as separate entries, never invent a period.
        start = 0
        for index in _deadline_slashes(chunk):
            following = chunk[index + 1:].strip().lstrip("'‘’")
            if _DATE.search(chunk[start:index]) and _DATE.match(following):
                lines.append(chunk[start:index].strip())
                start = index + 1
        lines.append(chunk[start:].strip())

    result, seen = [], set()
    for line in lines:
        formatted = _deadline_dates(" ".join(line.split()))
        key = re.sub(r"\s+", "", formatted)
        if formatted and key not in seen:
            result.append(formatted)
            seen.add(key)
    return result
