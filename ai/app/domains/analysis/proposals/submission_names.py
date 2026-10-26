"""Compare document names without discarding applicant or language qualifiers."""

import re
import unicodedata
from dataclasses import dataclass

from app.domains.analysis.schemas.result import EvidenceOrigin, RequirementSource

_FORM_NOTE = re.compile(
    r"(?:(?P<kind>붙임|별첨|첨부|별지|서식|양식)\s*(?:제\s*)?"
    r"(?P<number>\d+(?:-\d+)?)(?:\s*호)?(?:\s*(?:양식|서식))?|양식|서식)"
)
_SUBMITTER_NOTE = re.compile(
    r"(?P<subject>대학(?:교)?|기업|기관|(?:주관|공동|참여)(?:연구개발)?기관)\s*제출\s*용"
)


_RECENT_PERIOD = re.compile(
    r"최근\s*(?P<years>[1-9]\d?)\s*(?:개\s*)?(?:회계\s*연도|년도|년)(?:\s*말)?\s*"
)


@dataclass(frozen=True)
class SubmissionName:
    key: str
    references: tuple[str, ...]
    submitters: tuple[str, ...]
    period: int | None = None


def _compact(value: str) -> str:
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", value)).casefold()


def submission_name(title: str, *, split_period: bool = False) -> SubmissionName:
    """Split known notes, retaining unknown qualifiers and explicit submitter identity."""
    title = unicodedata.normalize("NFKC", title)
    references = set()
    submitters = set()
    period = None
    if split_period and (match := _RECENT_PERIOD.match(title)):
        period = int(match["years"])
        title = re.sub(r"\s*제출\s*$", "", title[match.end():])

    def parenthesis(match: re.Match[str]) -> str:
        remaining = []
        for part in re.split(r"[,;·]", match[1]):
            part = part.strip()
            form = _FORM_NOTE.fullmatch(part)
            subject = _SUBMITTER_NOTE.fullmatch(part)
            if form:
                if form["number"]:
                    references.add(f"{form['kind']}{form['number']}")
            elif subject:
                submitters.add(_compact(subject["subject"]))
            else:
                remaining.append(part)
        return "(" + ",".join(remaining) + ")" if remaining else ""

    name = re.sub(r"\(([^()]*)\)", parenthesis, title)
    return SubmissionName(
        _compact(name), tuple(sorted(references)), tuple(sorted(submitters)), period,
    )


def same_period_submission_source(
    left: RequirementSource | None, right: RequirementSource | None, key: str, period: int,
) -> bool:
    """Match a complete period requirement repeated in a notice and its attachment.

    The period prefix alone is not identity evidence. Keep concrete year ranges,
    copy requirements and other conditions in the comparison.
    """
    origins = {EvidenceOrigin.NOTICE_BODY, EvidenceOrigin.ATTACHMENT}
    if not left or not right or left.origin not in origins or right.origin not in origins:
        return False
    if any(s.origin == EvidenceOrigin.ATTACHMENT and not s.attachment_name for s in (left, right)):
        return False
    if (left.origin == right.origin == EvidenceOrigin.ATTACHMENT
            and left.attachment_name != right.attachment_name):
        return False

    shared = period_submission_evidence(left, key) & period_submission_evidence(right, key)
    return any(years == period and len(clause) >= len(key) + 8 for years, clause in shared)


def period_submission_evidence(source: RequirementSource, key: str) -> frozenset[tuple[int, str]]:
    """Keep period and year/copy details when comparing otherwise identical rows."""
    quote = unicodedata.normalize("NFKC", source.excerpt)
    result = set()
    for match in _RECENT_PERIOD.finditer(quote):
        # A filename starts separate upload instructions, not another requirement clause.
        clause = re.split(r"(?:\n\s*[*•☞]?\s*)?파일명\s*[:：]", quote[match.end():], maxsplit=1)[0]
        clause = _compact(clause)
        if clause.startswith(key):
            result.add((int(match["years"]), clause))
    return frozenset(result)


def same_submission_source(left: RequirementSource | None, right: RequirementSource | None) -> bool:
    """Require overlapping row citations in the same source, not just a shared filename."""
    if not left or not right or left.origin != right.origin:
        return False
    if left.origin == EvidenceOrigin.ATTACHMENT:
        if not left.attachment_name or left.attachment_name != right.attachment_name:
            return False
    elif left.origin != EvidenceOrigin.NOTICE_BODY:
        return False
    first, second = _compact(left.excerpt), _compact(right.excerpt)
    return min(len(first), len(second)) >= 15 and (first in second or second in first)
