"""Select a submission page and an eligibility page before generic OCR candidates."""

import re

_SUBMISSION = re.compile(r"제출\s*(?:서류|자료)|구비\s*서류|신청\s*서류|서류\s*목록")
_ELIGIBILITY = re.compile(
    r"신청\s*(?:자격|요건)|지원\s*(?:대상|자격|요건)|참여\s*(?:자격|요건)|"
    r"(?:신청|지원|참여)\s*(?:제외|제한)|자격\s*요건|신청\s*불가"
)
_TOC = re.compile(r"(?:^|\n)\s*(?:목\s*차|차\s*례)\s*(?:\n|$)|\.{3,}\s*\d+\s*(?:\n|$)")


def _score(text: str, pattern: re.Pattern) -> int:
    if _TOC.search(text):
        return 0
    matches = list(pattern.finditer(text))
    if not matches:
        return 0
    headings = sum(
        bool(pattern.search(line)) and len(line.strip()) <= 70
        for line in text.splitlines()
    )
    return 10 + min(len(matches), 10) + min(headings, 3) * 20


def select_ocr_pages(texts: list[str], candidates: list[int], limit: int) -> list[int]:
    """At most one best page per topic; a shared page is requested only once."""
    selected = []
    found_topics = 0
    for pattern in (_SUBMISSION, _ELIGIBILITY):
        scores = [(index, _score(text, pattern)) for index, text in enumerate(texts)]
        best = max(scores, key=lambda pair: (pair[1], -pair[0]), default=(-1, 0))
        if best[1]:
            found_topics += 1
            if best[0] not in selected:
                selected.append(best[0])
    # Unsearchable scans have no reliable topic signal before the paid call.
    if found_topics < 2:
        for index in candidates:
            if index not in selected and not _TOC.search(texts[index]):
                selected.append(index)
            if len(selected) >= limit:
                break
    return selected[:limit]
