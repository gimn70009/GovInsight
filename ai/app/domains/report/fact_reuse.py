"""Reuse short labelled facts only after checking the current report sources.

Stored analysis prose is a candidate, never evidence. Complex, conflicting and
incomplete passages remain the report model's responsibility.
"""

import re
import unicodedata

from app.domains.report.facts import (
    _find,
    _source_lines,
    _usable_applicant,
    _usable_deadline,
)
from app.domains.report.presentation import _anchors, readable_source
from app.domains.report.schemas.request import ReportDocumentRequest
from app.domains.report.submission_documents import _parts, source_priority

FACT_FIELDS = {
    "applicants": "applicant",
    "deadlines": "deadline",
    "destinations": "destination",
    "contacts": "contact",
}
_LABELS = {
    "applicant": r"(?:신청|지원|제출|참여)\s*(?:대상|주체|자격|기관)\s*[:：]",
    "deadline": r"(?:접수|신청|제출|의견\s*제출)\s*(?:기간|기한|마감|일시)\s*[:：]",
    "destination": r"(?:접수|신청|제출)\s*(?:방법|방식|처|장소|경로)\s*[:：]",
    "contact": r"(?:문의(?:처|사항)?|담당(?:자|부서))\s*[:：]",
}
_UNSAFE = re.compile(
    r"일부\s*원문\s*생략|참고\s*용|작성\s*예시|견본|"
    r"(?:선정|협약)(?:\s*체결)?\s*(?:이후|후|시)|"
    r"공모방식|공고예산|취급금융기관|유의사항|외\s*\d+개\s*안내"
)
_UNSAFE_HEADING = re.compile(
    r"(?m)^\s*[□■○◦●ㅇ\d.)\s-]*(?:참고(?:자료|용)?|예시|견본|작성\s*(?:요령|예시)|"
    r"(?:선정|협약)\s*(?:이후|후|시))\s*[:：\n]"
)


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value))


def _lines(text: str) -> list[str]:
    # HTML text may flatten a whole notice; restore explicit outline separators.
    text = re.sub(r"([□■○◦●])", r"\n\1", text)
    text = re.sub(
        r"[ \t]+(?=(?:[ⅠⅡⅢⅣⅤⅥⅦ]+\.|(?:[가-하]|[0-9]+)[.)])[ \t]*"
        r"(?:기\s*타|문의|신청|접수|제출|지원|사업|평가|선정|참가))",
        "\n\n", text,
    )
    text = re.sub(r"(?m)^[ \t]*◦[ \t]*", "", text)
    return _source_lines([text])


def _source_key(value: str) -> str:
    return re.sub(r"[□■○◦●]", "", _compact(value.replace(" / ", " ")))


def _complete(value: str, kind: str) -> bool:
    if not 2 <= len(value) <= 150 or _UNSAFE.search(value):
        return False
    if not readable_source(value, kind) or value.endswith((",", ":", "/", "-")):
        return False
    if any(value.count(left) != value.count(right) for left, right in (("(", ")"), ("[", "]"))):
        return False
    if kind == "applicant":
        return bool(_usable_applicant(value))
    if kind == "deadline":
        return bool(_usable_deadline(value)) and not re.search(
            r"제출\s*방법|제출\s*서류|문의처|붙임|동의서|억원|평가|협약|출연금|지원기간", value
        )
    if kind == "destination":
        return bool(re.search(r"제출|접수|온라인|이메일|우편|방문|https?://", value))
    return bool(re.search(r"[\w.+-]+@[\w.-]+|\d{2,4}[-)]\s*\d{3,4}[-~]\d{4}", value))


def verified_submission_facts(document: ReportDocumentRequest) -> dict[str, str]:
    """Return complete field values; a single unsafe candidate blocks that field.

    Dates/eligibility cross-check prior analysis against a current labelled
    source passage. Routes/contacts are not stored upstream and are verified
    from the same source passages locally, without another model call.
    """
    comparison = document.comparison_summary
    if comparison is None:
        return {}
    parts = [p for p in _parts(document) if source_priority(p) == 0 and p.text.strip()]
    # Missing tails can contain a different country/round or an exception.
    if any(
        "[일부 원문 생략" in p.text
        or _UNSAFE_HEADING.search(re.sub(r"([□■○◦●])", r"\n\1", p.text))
        for p in parts
    ):
        return {}
    source_text = "\n".join(p.text for p in parts)
    deadline_rules = {
        token for pattern, token in (
            (r"도착", "도착"),
            (r"(?<![가-힣])소인(?:분|까지|기준)?", "소인"),
            (r"예산\s*소진", "예산소진"),
            (r"동시(?:에)?\s*(?:제출|접수|신청)", "동시"),
        ) if re.search(pattern, source_text)
    }
    candidates: dict[str, list[str]] = {kind: [] for kind in _LABELS}
    blocked = set()
    for part in parts:
        for line in _lines(part.text):
            for kind, pattern in _LABELS.items():
                if not re.search(pattern, line):
                    continue
                if len(re.findall(pattern, line)) > 1:
                    blocked.add(kind)
                    continue
                value = _find([line], pattern, applicant=kind == "applicant")
                if value and kind == "contact":
                    value = re.sub(r"^(?:관련\s*)?문의(?:처)?\s*[:：]\s*", "", value)
                if not value or not _complete(value, kind):
                    blocked.add(kind)
                    continue
                # Locally joined wrapped lines must still occur in this file,
                # in order, without borrowing words from another source.
                raw = _source_key(value)
                if raw not in _source_key(part.text):
                    blocked.add(kind)
                    continue
                if raw not in {_source_key(v) for v in candidates[kind]}:
                    candidates[kind].append(value)
    reused = {}
    for kind, values in candidates.items():
        # Conflicting dates and multi-route/role lists need model interpretation.
        if kind in blocked or len(values) != 1:
            continue
        value = values[0]
        if kind == "applicant":
            if _compact(comparison.eligibility or "") != _compact(value):
                continue
        elif kind == "deadline":
            saved_dates = {
                anchor for anchor in _anchors(comparison.application_deadline or "")
                if anchor.startswith("date:")
            }
            if not saved_dates or not saved_dates <= _anchors(value):
                continue
            # The stored summary can flag an important rule outside the date row.
            rules = re.findall(
                r"도착|소인|예산\s*소진|동시|국내|국외|해외|우편|"
                r"[가-힣]{2,12}(?:측|표준시)",
                comparison.application_deadline or "",
            )
            if any(_compact(rule) not in _compact(value) for rule in [*rules, *deadline_rules]):
                continue
        reused[kind] = value
    return reused
