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


@dataclass(frozen=True)
class SubmissionName:
    key: str
    references: tuple[str, ...]
    submitters: tuple[str, ...]


def _compact(value: str) -> str:
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", value)).casefold()


def submission_name(title: str) -> SubmissionName:
    """Split known notes, retaining unknown qualifiers and explicit submitter identity."""
    title = unicodedata.normalize("NFKC", title)
    references = set()
    submitters = set()

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
    return SubmissionName(_compact(name), tuple(sorted(references)), tuple(sorted(submitters)))


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
