"""Conservative calendar-date selection for application closure decisions."""

import re
from datetime import date

_DATE = re.compile(
    r"(?<!\d)(20\d{2})\s*(?:[-./]|년)\s*(\d{1,2})\s*(?:[-./]|월)\s*"
    r"(\d{1,2})(?!\d)(?:\s*일|\.)?"
)
_PARTIAL_DATE = re.compile(
    r"(?<!\d)(?:\d{1,2}\s*월\s*\d{1,2}\s*일|\d{1,2}[-./]\d{1,2})(?![\d%])"
)
_RANGE = re.compile(r"[~∼～–—-]|부터|\bto\b", re.IGNORECASE)
_DATE_SUFFIX = re.compile(
    r"\s*(?:\([^()\n]*\))?"
    r"(?:[T\s]+(?:오전|오후)?\s*\d{1,2}(?::\d{2}|시(?:\s*\d{1,2}\s*분)?))?\s*"
)
_DEADLINE_LABEL = re.compile(
    r"(?:(?:신청(?:서)?|접수|제출)\s*"
    r"(?:마감(?:일시?|시각)?|종료일|기한|기간)|마감(?:일시?|시각))"
    r"\s*(?:[은는이가]\s*)?[:：]?\s*(?=[~∼～]?\s*20\d{2})"
)
_OTHER_DATE_ROLE = re.compile(
    r"(?:분석|공고|게시|작성|기준|발표|평가|심사|행사)(?:일시?|날짜|기간|시각)"
    r"|(?:분석|공고|게시|작성|발표|평가|심사|행사)\s*기준"
    r"|(?:사업|신청|접수|제출)\s*(?:시작|종료|개시)(?:일시?|날짜|시각)?"
    r"|사업\s*기간"
)
_NON_APPLICATION_LABEL = re.compile(r"(?:평가|발표|심사|행사|공고|게시|작성|사업)\s*$")
_OPEN_END = re.compile(r"시작|개시|이후|미정|미확정|상시|예산\s*소진|별도\s*공지")


def has_calendar_date(text: str) -> bool:
    """Include incomplete dates, which must not fall back to a closure claim."""
    return bool(re.search(r"20\d{2}", text) or _PARTIAL_DATE.search(text))


def application_deadline(text: str, *, require_label: bool = False) -> date | None:
    """Read a deadline field, or only explicitly labelled dates in model prose.

    Missing years and unrelated dates remain unknown. A labelled analysis/event
    date can follow the deadline but cannot become a deadline itself.
    """
    labels = list(_DEADLINE_LABEL.finditer(text))
    if any(
        label.group().startswith("마감")
        and _NON_APPLICATION_LABEL.search(text[:label.start()])
        for label in labels
    ):
        return None
    if labels:
        values = []
        role_boundaries = [_role_boundary(text, role) for role in _OTHER_DATE_ROLE.finditer(text)]
        boundaries = sorted([
            *(label.start() for label in labels),
            *(position for position in role_boundaries if position is not None),
        ])
        for label in labels:
            end = next((position for position in boundaries if position >= label.end()), len(text))
            value = _date_value(text[label.end():end])
            if value is None:
                return None
            values.append(value)
        return values[0] if len(set(values)) == 1 else None
    if require_label or _OTHER_DATE_ROLE.search(text):
        return None
    return _date_value(text)


def resolve_application_deadline(field: str, reason: str) -> date | None:
    confirmed = application_deadline(field)
    if confirmed is not None:
        return confirmed
    inferred = application_deadline(reason, require_label=True)
    if inferred is None or not has_calendar_date(field):
        return inferred
    # A prose date may complete a matching yearless range end, but must not
    # choose between conflicting full dates or replace a malformed deadline.
    return inferred if _completes_partial_period(field, inferred) else None


def _completes_partial_period(text: str, endpoint: date) -> bool:
    matches = list(_DATE.finditer(text))
    if len(matches) != 1 or _OTHER_DATE_ROLE.search(text) or _OPEN_END.search(text):
        return False
    start = _calendar_date(matches[0])
    if start is None or endpoint < start:
        return False
    suffix = _DATE_SUFFIX.sub("", text[matches[0].end():], count=1)
    connector = _RANGE.match(suffix)
    if connector is None:
        return False
    suffix = suffix[connector.end():].lstrip()
    short_end = re.match(r"(\d{1,2})\s*(?:월|[-./])\s*(\d{1,2})(?!\d)(?:\s*일|\.)?", suffix)
    if short_end is None or has_calendar_date(suffix[short_end.end():]):
        return False
    return (endpoint.month, endpoint.day) == tuple(map(int, short_end.groups()))


def _role_boundary(text: str, role: re.Match[str]) -> int | None:
    # A role boundary requires an adjacent date, not a word such as "공고 변경".
    dates = list(_DATE.finditer(text[:role.start()]))
    if dates and not text[dates[-1].end():role.start()].strip():
        return dates[-1].start()
    tail = text[role.end():]
    separator = re.match(r"\s*(?:[은는이가]\s*)?[:：]?\s*", tail)
    return role.start() if _DATE.match(tail, separator.end()) else None


def single_event_date(text: str) -> date | None:
    """Event narration checks do not imply an application deadline."""
    matches = list(_DATE.finditer(text))
    return _calendar_date(matches[0]) if len(matches) == 1 else None


def _calendar_date(match: re.Match[str]) -> date | None:
    try:
        return date(*(int(part) for part in match.groups()))
    except ValueError:
        return None


def _date_value(text: str) -> date | None:
    matches = list(_DATE.finditer(text))
    if not 1 <= len(matches) <= 2:
        return None
    values = [_calendar_date(match) for match in matches]
    if any(value is None for value in values):
        return None
    # A short second endpoint or malformed extra date is not a complete range.
    remainder = _DATE.sub("", text)
    if has_calendar_date(remainder):
        return None
    if len(matches) == 1:
        if (
            _RANGE.search(text[matches[0].end():])
            or "부터" in text[:matches[0].start()]
            or _OPEN_END.search(text)
        ):
            return None
        return values[0]
    bridge = text[matches[0].end():matches[1].start()]
    bridge = _DATE_SUFFIX.sub("", bridge, count=1)
    if not _RANGE.fullmatch(bridge.strip()):
        return None
    if _RANGE.search(text[matches[1].end():]) or values[1] < values[0]:
        return None
    return values[1]
