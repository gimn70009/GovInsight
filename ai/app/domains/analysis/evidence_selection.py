"""Deterministic, source-preserving excerpts; no generated summaries or model calls."""

import re
from dataclasses import dataclass

_TOPICS = tuple(
    re.compile(pattern)
    for pattern in (
        r"신청\s*자격|지원\s*대상|참여\s*자격|제외|불가|제한|단,|다만",
        r"접수|마감|제출|신청\s*기간|변경|정정|연장",
        r"지원\s*(?:금|규모|내용)|사업비|부담|예산|억원|백만원",
        r"목적|사업\s*개요|수행|과업|컨소시엄|주관|공동|협약",
        r"문의|담당|연락|전화|이메일",
    )
)
_HEADING = re.compile(r"(?m)^(?:[ \t]*)(?:[□■○]\s*|\d+[.)]\s+|[가-하][.)]\s+)")
_GAP = "\n[일부 원문 생략: 누락 부분은 확인되지 않음]\n"


@dataclass(frozen=True)
class Excerpt:
    text: str
    original_chars: int
    ranges: tuple[tuple[int, int], ...]
    truncated: bool

    def metadata(self) -> dict:
        return {
            "originalChars": self.original_chars,
            "selectedChars": sum(end - start for start, end in self.ranges),
            "truncated": self.truncated,
            "sourceRanges": [{"start": start, "end": end} for start, end in self.ranges],
        }


def _units(text: str) -> list[tuple[int, int]]:
    # Preserve short sections (including exceptions and list/table rows) as one unit.
    boundaries = {0, len(text)}
    boundaries.update(m.end() for m in re.finditer(r"\r?\n[ \t]*\r?\n", text))
    boundaries.update(m.start() for m in _HEADING.finditer(text))
    points = sorted(boundaries)
    result = []
    for start, end in zip(points, points[1:]):
        if end - start > 1600:
            # Only use complete lines/sentences, never cut a condition at a character count.
            cuts = [
                start,
                *[start + m.end() for m in re.finditer(r"\n|(?<=[다요][.!?])\s+", text[start:end])],
                end,
            ]
            group_start = cuts[0]
            for left, right in zip(cuts, cuts[1:]):
                if right - group_start > 1600 and left > group_start:
                    result.append((group_start, left))
                    group_start = left
            result.append((group_start, end))
        else:
            result.append((start, end))
    merged: list[tuple[int, int]] = []
    for start, end in result:
        if not text[start:end].strip():
            continue
        if merged and re.match(r"\s*(?:단[,，]|다만|※)", text[start:end]):
            merged[-1] = (merged[-1][0], end)
        else:
            merged.append((start, end))
    return merged


def select_evidence(text: str | None, limit: int) -> Excerpt:
    source = text or ""
    limit = max(0, limit)
    if len(source) <= limit:
        return Excerpt(source, len(source), ((0, len(source)),) if source else (), False)
    if limit <= len(_GAP):
        return Excerpt("", len(source), (), True)
    units = _units(source)
    topics = [
        set(i for i, pattern in enumerate(_TOPICS) if pattern.search(source[s:e])) for s, e in units
    ]
    chosen: list[int] = []
    covered: set[int] = set()
    seen: set[str] = set()
    remaining = limit - len(_GAP)
    # Greedy topic coverage avoids spending the budget on repeated introductions.
    pending = set(range(len(units)))
    ranked: list[int] = []
    while pending:
        if all(topics[i] <= covered for i in pending):
            ranked = sorted(
                pending, key=lambda i: (-len(topics[i]), i != 0, i != len(units) - 1, i)
            )
            break
        index = max(pending, key=lambda i: (len(topics[i] - covered), len(topics[i]), i == 0, -i))
        pending.remove(index)
        start, end = units[index]
        signature = " ".join(source[start:end].split())
        cost = end - start + len(_GAP)
        if cost <= remaining and signature not in seen:
            chosen.append(index)
            remaining -= cost
            seen.add(signature)
        # Coverage drives ordering only; rejected units do not become evidence.
        covered.update(topics[index])
    for index in ranked:
        start, end = units[index]
        signature = " ".join(source[start:end].split())
        cost = end - start + len(_GAP)
        if cost <= remaining and signature not in seen and len(chosen) < 64:
            chosen.append(index)
            remaining -= cost
            seen.add(signature)
    ranges = tuple(units[i] for i in sorted(chosen))
    # Explicit separators prevent disconnected excerpts from looking like contiguous clauses.
    rendered = _GAP.join(source[s:e] for s, e in ranges)
    if ranges and ranges[0][0] > 0:
        rendered = _GAP + rendered
    if not ranges or ranges[-1][1] < len(source):
        rendered += _GAP
    return Excerpt(rendered if len(rendered) <= limit else "", len(source), ranges, True)


def allocate_budgets(lengths: list[int], total: int) -> list[int]:
    """Water filling: every file gets a share, short files return unused capacity."""
    budgets = [0] * len(lengths)
    active = [i for i, length in enumerate(lengths) if length > 0]
    remaining = max(0, total)
    while active and remaining:
        share = max(1, remaining // len(active))
        for i in active:
            amount = min(share, lengths[i] - budgets[i], remaining)
            budgets[i] += amount
            remaining -= amount
        active = [i for i in active if budgets[i] < lengths[i]]
    return budgets
