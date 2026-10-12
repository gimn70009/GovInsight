"""Final schedule checks; prior summaries are never used as source evidence."""

import re

from app.domains.report.facts import _DATE
from app.domains.report.presentation import _anchors, informational_notice
from app.domains.report.schedules import schedule_fields

_SCHEDULE = re.compile(
    r"(?P<closing>(?:(?:신청|접수|제출|의견\s*제출)\s*)?"
    r"(?:마감(?:일자|일)?|기한)|(?:신청|접수|제출)\s*기간)"
    r"|공고(?:일자|일)|게시일|등록일|"
    r"(?:신청|접수|제출)\s*(?:시작|개시)(?:일)?|"
    r"(?:평가|사업|지원|시상|설명회|행사|선정|발표|협약)\s*(?:기간|일정|일시|일자|일)"
)
_CONTEXT = re.compile(r"국내|국외|해외|국문|영문|한국|[가-힣]{2,10}측|\d+차")


def _context(prefix: str) -> str:
    # Only the current clause can supply a country/language caption. A country
    # mentioned earlier in an explanation must not bind a later unlabelled date.
    prefix = re.split(r"[,;\n]|(?<=[다요])[.!?]\s+", prefix)[-1]
    prefix = re.sub(r"(?:연구개발계획서|연구계획서|계획서)\s*$", "", prefix).strip()
    contexts = _CONTEXT.findall(prefix)
    if len(contexts) == 1:
        return "해외" if contexts[0] == "국외" else contexts[0]
    if len(contexts) > 1:
        return "?"
    # Preserve other explicit country captions at the start of a clause.
    return prefix if re.fullmatch(r"[가-힣]{2,12}", prefix) else ""


def _endpoint(text: str) -> str | None:
    matches = list(_DATE.finditer(text))
    if not matches or len(matches) > 2:
        return None
    if len(matches) == 2 and not re.search(
        r"[~∼～]|부터", text[matches[0].end():matches[1].start()]
    ):
        # Two unordered date cells have no proven closing endpoint.
        return None
    return next((a for a in _anchors(matches[-1][0]) if a.startswith("date:")), None)


def _closing_claims(text: str) -> list[tuple[str, str]]:
    headings = list(_SCHEDULE.finditer(text))
    claims = []
    for index, heading in enumerate(headings):
        if not heading["closing"]:
            continue
        end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
        scope = re.split(r"(?<=[다요])[.!?]\s+|\n|;", text[heading.end():end])[0]
        endpoint = _endpoint(scope)
        if endpoint:
            # Context is immediately before this heading, after the preceding
            # schedule's date/statement or an explicit clause separator.
            start = headings[index - 1].end() if index else 0
            prefix = text[start:heading.start()]
            claims.append((_context(prefix), endpoint))
    return claims


def _same_date(left: str, right: str) -> bool:
    ly, lm, ld = left.removeprefix("date:").split("-")
    ry, rm, rd = right.removeprefix("date:").split("-")
    return (lm, ld) == (rm, rd) and (not ly or not ry or ly == ry)


def _unambiguous(dates: list[str]) -> bool:
    return bool(dates) and all(_same_date(left, right) for left in dates for right in dates)


def schedule_consistency_issues(document, deadline: str) -> tuple[str, ...]:
    if informational_notice(document.title, document.content_text or ""):
        return ()
    claimed = _closing_claims(document.summary or "")
    if not claimed:
        return ()
    actual = []
    for line in schedule_fields(deadline)["제출·의견 기한"]:
        entries = _closing_claims(line)
        if entries:
            actual.extend(entries)
        elif endpoint := _endpoint(line):
            # The verified field may retain an original "...까지" excerpt.
            actual.append(("", endpoint))
    if not actual:
        return ("deadline:summary_without_verified_schedule",)
    for context, endpoint in claimed:
        if context == "?":
            continue
        peers = [date for role, date in claimed if role == context]
        candidates = [date for role, date in actual if role == context] if context else [
            date for _, date in actual
        ]
        # Missing or multiple possible role mappings are not proof of a conflict.
        if not _unambiguous(peers) or not _unambiguous(candidates):
            continue
        if not _same_date(endpoint, candidates[0]):
            return ("deadline:summary_conflict",)
    return ()
