"""Carry original program ownership through selection and bind it to exact citations."""

from .schemas import ScopeRegion


def rebase(source, excerpt):
    """Selection may join disjoint spans. Rebase each retained original line, never a gap."""
    regions, cursor, offset = [], 0, 0
    for line in excerpt.splitlines(keepends=True):
        at = source.excerpt.find(line, cursor)
        if at >= 0:
            for region in source.scope_regions:
                start, end = max(at, region.start), min(at + len(line), region.end)
                if start < end:
                    rebased = ScopeRegion(
                        start=offset + start - at, end=offset + end - at, scope=region.scope
                    )
                    if (
                        regions
                        and regions[-1].end == rebased.start
                        and regions[-1].scope == rebased.scope
                    ):
                        regions[-1] = regions[-1].model_copy(update={"end": rebased.end})
                    else:
                        regions.append(rebased)
            cursor = at + len(line)
        offset += len(line)
    return source.model_copy(update={"excerpt": excerpt, "scope_regions": regions})


def windows(source):
    """Use the same physical L references as the citation protocol, including table cells."""
    if not source.scope_regions:
        return []
    intervals, cursor = [], 0
    for r in sorted(source.scope_regions, key=lambda r: r.start):
        if r.start > cursor:
            intervals.append((cursor, r.start, None))
        intervals.append((r.start, r.end, r.scope))
        cursor = max(cursor, r.end)
    if cursor < len(source.excerpt):
        intervals.append((cursor, len(source.excerpt), None))
    return [
        {
            "lines": f"L{source.excerpt.count(chr(10), 0, start) + 1}-"
            f"L{source.excerpt.count(chr(10), 0, max(start, end - 1)) + 1}",
            "scope": scope,
        }
        for start, end, scope in intervals
    ]


def resolve(source, quote):
    """Return one enclosing owner, or ambiguity. Labels from neighboring rows are invalid."""
    owners, ambiguous, at = set(), False, source.excerpt.find(quote)
    while quote and at >= 0:
        end = at + len(quote)
        regions = [r for r in source.scope_regions if r.end > at and r.start < end]
        if regions:
            owners.update(r.scope for r in regions)
            ambiguous |= not any(r.start <= at and r.end >= end for r in regions)
        else:
            owners.add(None)
        at = source.excerpt.find(quote, at + 1)
    if not owners or owners == {None}:
        return None, False
    if ambiguous or len(owners) != 1 or "적용 범위 확인 필요" in owners:
        return None, True
    return next(iter(owners)), False
