"""Report-only, source-preserving selection of application guidance."""

import re
from dataclasses import dataclass

from app.domains.analysis.evidence.selection import Excerpt

_GAP = "\n[일부 원문 생략: 누락 부분은 확인되지 않음]\n"
_FIELD = (
    r"(?:포상\s*)?신청\s*(?:자격|대상|기간|방법|요령|서류|및\s*접수)"
    r"|접수\s*(?:기간|기한|마감|방법|처)|제출\s*(?:기한|마감|방법|서류|처)"
    r"|지원\s*(?:대상|자격|규모|금액|방식)|사업비|예산액|기초금액|추정가격|자부담|참여\s*자격|문의(?:\s*및\s*연락처|처)?|담당(?:자|부서)|연락처"
)
_SECTION = (
    _FIELD + r"|사업\s*(?:개요|목적|내용)|지원\s*(?:내용|규모)|평가|선정|협약"
    r"|중복성|이의\s*신청|기타|유의\s*사항|포상|신청서|작성|붙임|별첨|참고|추진|근거"
)
_OUTLINE = re.compile(
    r"(?m)(?:^|(?<=\s))(?:[□■○●◆◇▶]|\d+(?:-\d+)*[.)]|[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ])"
    r"\s*(?=(?:" + _SECTION + r"))"
)
_LABEL = re.compile(r"(?m)(?:^|(?<=\s))(?:" + _FIELD + r")\s*[:：]")
_LOW_PRIORITY = re.compile(
    r"중복성|이의\s*신청|협약|선정\s*후|선정된|작성\s*(?:요령|예시)|참고자료"
)
_CONDITION = re.compile(r"\s*(?:[※*]|단\s*[,，:]|다만|예외|단서)")
_PHONE = re.compile(r"(?<!\d)0\d{1,2}[- )]+\d{3,4}[- ]\d{4}(?!\d)")
_EMAIL = re.compile(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}")
_DATE = re.compile(r"\d{2,4}\s*[.년/-]\s*\d{1,2}\s*[.월/-]\s*\d{1,2}")
_TOPICS = tuple(re.compile(pattern) for pattern in (
    r"신청\s*자격|신청\s*대상|지원\s*(?:대상|자격)|참여\s*자격|주관.*기관|"
    r"제외\s*대상|컨소시엄|참여\s*필수|필수\s*참여",
    r"(?:신청|접수|제출)\s*(?:기간|기한|마감)|포상\s*신청\s*기간",
    r"(?:신청|접수|제출)\s*(?:방법|요령|처)|온라인\s*접수|우편\s*(?:접수|제출)",
    r"문의|담당|연락처|전화|이메일",
    r"(?:제출|구비|신청)\s*서류|제출\s*목록",
    r"지원\s*(?:규모|금액|한도|방식)|사업비|연구개발비|보조금|출연금|"
    r"바우처|융자|보증|사업예산|예산액|추정가격|기초금액|자부담",
    r"사업\s*(?:개요|목적|내용)|운영기관|수행기관|모집\s*대상",
))


_FUNDING_AMOUNT = re.compile(r"\d[\d,.]*\s*(?:천|만|백만|천만|억)?\s*원")
_FUNDING_SCOPE = re.compile(
    r"(?:과제|기업|업체)당|지원\s*(?:규모|금액|한도|금)|"
    r"신규\s*예산|사업\s*예산|예산액|추정(?:가격|금액)|기초금액|계약(?:금액|규모)"
)


@dataclass(frozen=True)
class _Unit:
    start: int
    end: int
    low_priority: bool
    context_start: int | None = None


def _units(source: str) -> list[_Unit]:
    # Strong headings work both in physical paragraphs and flattened HTML text.
    boundaries = {0, len(source)}
    boundaries.update(match.start() for match in _OUTLINE.finditer(source))
    boundaries.update(match.start() for match in _LABEL.finditer(source))
    boundaries.update(match.end() for match in re.finditer(r"\n[ \t]*\n", source))
    points = sorted(boundaries)
    result: list[_Unit] = []
    low = False
    context_start = None
    for start, end in zip(points, points[1:]):
        section = source[start:end]
        if start == 0 or _OUTLINE.match(source, start):
            low = bool(_LOW_PRIORITY.search(section[:100]))
            context_start = start if low else None
        # Never truncate an unbroken clause by a character offset. For large
        # sections, pack complete rows/sentences and retain adjacent exceptions.
        cuts = [start]
        cuts.extend(start + match.end() for match in re.finditer(
            r"\n|(?<=[다요][.!?])\s+|\s+(?=[ㅇ•]\s+[가-힣])", section,
        ))
        cuts.append(end)
        group = start
        for left, right in zip(cuts, cuts[1:]):
            if right - group > 650 and left > group and not _CONDITION.match(source[left:right]):
                result.append(_Unit(group, left, low, context_start))
                group = left
        if source[group:end].strip():
            result.append(_Unit(group, end, low, context_start))
    # A separately headed note still belongs to its immediately preceding clause.
    merged: list[_Unit] = []
    for unit in result:
        if merged and _CONDITION.match(source[unit.start:unit.end]):
            prior = merged.pop()
            merged.append(_Unit(prior.start, unit.end, prior.low_priority, prior.context_start))
        else:
            merged.append(unit)
    return merged


def _ranges(units: list[_Unit], chosen: set[int]) -> tuple[tuple[int, int], ...]:
    ranges: list[tuple[int, int]] = []
    candidates = sorted(
        (units[index].context_start if units[index].context_start is not None
         else units[index].start, units[index].end) for index in chosen
    )
    for start, end in candidates:
        if ranges and start <= ranges[-1][1]:
            ranges[-1] = (ranges[-1][0], max(ranges[-1][1], end))
        else:
            ranges.append((start, end))
    return tuple(ranges)


def _render(source: str, ranges: tuple[tuple[int, int], ...]) -> str:
    if not ranges:
        return ""
    text = _GAP.join(source[start:end] for start, end in ranges)
    if ranges[0][0] > 0:
        text = _GAP + text
    if ranges[-1][1] < len(source):
        text += _GAP
    return text


def select_report_evidence(text: str | None, limit: int) -> Excerpt:
    """Preserve complete source ranges while balancing application-specific fields."""
    source = text or ""
    limit = max(0, limit)
    if len(source) <= limit:
        return Excerpt(source, len(source), ((0, len(source)),) if source else (), False)
    if limit <= len(_GAP):
        return Excerpt("", len(source), (), True)
    units = _units(source)
    contents = [source[unit.start:unit.end] for unit in units]
    topics = [{i for i, pattern in enumerate(_TOPICS) if pattern.search(value)}
              for value in contents]
    for index, value in enumerate(contents):
        if _PHONE.search(value) or _EMAIL.search(value):
            topics[index].add(3)
    signatures = [" ".join(value.split()) for value in contents]
    chosen: set[int] = set()
    seen: set[str] = set()

    def rank(index: int, topic: int | None = None) -> tuple:
        value = contents[index]
        concrete = bool(_DATE.search(value)) if topic == 1 else (
            bool(_PHONE.search(value) or _EMAIL.search(value)) if topic == 3 else
            bool(re.search(r"[:：]|https?://|필수|제외|기업|온라인|우편", value))
        )
        explicit = topic is not None and bool(_TOPICS[topic].search(value[:100]))
        # A heading or an incidental cost mention must not satisfy the funding
        # slot while an actual cap + participation table is dropped by the budget.
        substantive_funding = topic == 5 and bool(
            _FUNDING_SCOPE.search(value) and _FUNDING_AMOUNT.search(value)
        )
        return (units[index].low_priority, not substantive_funding, not explicit,
                not concrete, -len(topics[index]), index)

    def add(index: int) -> bool:
        if index in chosen or not signatures[index] or signatures[index] in seen:
            return False
        candidate = chosen | {index}
        if len(_render(source, _ranges(units, candidate))) > limit:
            return False
        chosen.add(index)
        seen.add(signatures[index])
        return True

    # Each requested kind gets a chance before repeated application prose consumes
    # the budget. Oversized candidates never mark their topic as covered.
    for topic in (1, 2, 3, 0, 5, 6, 4):
        candidates = (index for index in range(len(units)) if topic in topics[index])
        for index in sorted(candidates, key=lambda index: rank(index, topic)):
            if index in chosen or add(index):
                break
    for index in sorted(range(len(units)), key=rank):
        if len(chosen) >= 64:
            break
        add(index)
    ranges = _ranges(units, chosen)
    return Excerpt(_render(source, ranges), len(source), ranges, True)
