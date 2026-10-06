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



_TABLE_COLUMNS = re.compile(
    r"(?:^|\s)(부문|훈격|규모|수공기간|공적기간|포상대상|포상규모|인원|구분|합계|"
    r"지원대상|지원규모|지원기간)(?=\s|$)"
)


def unreadable_table(value: str) -> bool:
    """Reject flattened column/value streams; never guess which number is a condition."""
    text = " ".join(value.split())
    return (
        len(set(_TABLE_COLUMNS.findall(text))) >= 3
        or bool(re.search(r"(?<!\S)\d+\s+(?:계|합계|총계)\s+\d+(?!\S)", text))
        or len(re.findall(r"(?:주관|공동)기관자격", text)) > 1
    )


def source_check_message(label: str) -> str:
    subject = {
        "제출 주체·대상": "신청 자격과 제외 조건",
        "제출·의견 기한": "마감일과 접수 기준",
        "제출처·방법": "접수처와 제출 방법",
        "문의 담당": "담당 부서와 연락 방법",
        "사업 요약": "사업 내용과 세부 조건",
    }.get(label, label)
    return f"원문 표에서 {subject}을 확인해 주세요."


def korean_display(
    raw: str, quote: str, display: str | None, *, kind: str | None = None
) -> str | None:
    if display is None or unreadable_table(quote) or unreadable_table(raw):
        return None
    value = " ".join(display.split())
    if not value or len(value) > 150 or not re.search(r"[가-힣]", value) or foreign_prose(value):
        return None
    # Translation may change words, never invent or silently remove numeric/contact facts.
    if not _anchors(value) <= _anchors(quote) or not _anchors(raw) <= _anchors(value):
        return None

    # Bind each comparator to its quantity and unit, not just a set of directions.
    def bounds(text):
        return {
            re.sub(r"\s+", "", match)
            for match in re.findall(
                r"\d+(?:[.,]\d+)*\s*(?:%|[가-힣]+)?\s*(?:이상|이하|미만|초과)",
                unicodedata.normalize("NFKC", text),
            )
        }

    if bounds(raw) != bounds(value):
        return None
    # Do not ask a paraphrase to carry eligibility/exception semantics. Preserve
    # the complete cited Korean condition, including clauses omitted in fragments.
    preserve_applicant = (
        kind == "applicant" and re.search(r"[가-힣]", quote) and not foreign_prose(quote)
    )
    if preserve_applicant or _protected_condition(quote):
        # Flattening several role columns would attach a condition to the wrong party.
        if len(re.findall(r"(?:주관|공동)기관자격", quote)) > 1:
            return None
        source = " ".join(quote.split())
        return source if len(source) <= 150 else None
    if _protected_condition(value) and not _protected_condition(quote):
        return None
    return value


def _protected_condition(text: str) -> bool:
    return bool(re.search(r"[가-힣]", text) and not foreign_prose(text) and re.search(
        r"불가|제외|제한|한정|필수|반드시|의무|경우|예외|다만|단[,，:]|"
        r"없|않|못|금지|이상|이하|미만|초과|가능|유효|동시|에만|만\s*(?:신청|지원|참여|접수)",
        text,
    ))


def readable_source(value: str, kind: str) -> str | None:
    if len(value) > 150 or foreign_prose(value) or unreadable_table(value):
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


def submission_display_lines(value: str, label: str) -> list[str]:
    """Remove document outline noise without summarizing or changing any conditions."""
    if unreadable_table(value):
        return [source_check_message(label)]
    text = re.sub(r"(?:^|\s+)[ㅇ○●▪•□■◦▫◇▶▸]\s+", "\n", value).strip()
    if label == "제출 주체·대상":
        text = re.sub(
            r"^(?:[가-하][.)]\s*)?(?:신청\s*자격|신청\s*대상|지원\s*대상)"
            r"(?=\s|[:：]|$)\s*[:：]?\s*", "", text,
        )
    if "기한" in label:
        text = re.sub(r"^표제\s*[:：]\s*", "", text)
    # Korean sentence endings only: do not split dates, decimals, names or URLs.
    text = re.sub(r"(?<=[다요]\.)\s+(?=[가-힣])", "\n", text)
    text = text.replace("공.사", "공·사")
    return [line.strip() for line in text.splitlines() if line.strip()]
