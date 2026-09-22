"""Korean presentation checks, separate from source extraction and evidence matching."""

import re
import unicodedata

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
