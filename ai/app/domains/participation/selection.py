"""Bounded structural selection. No model calls, notice names, amounts or generated facts."""

import re

QUALIFICATION = re.compile(
    r"(?:신청|참여|지원|모집|기업)\s*(?:자격|대상|요건|조건)|선정\s*(?:대상|기준)|자격\s*요건"
)
PREFERENCE = re.compile(r"우대|가점|가산점")
IRRELEVANT = re.compile(
    r"(?:선정|협약)\s*후|유의\s*사항|문의|주요\s*용어|정의|향후\s*절차|평가\s*(?:절차|내용|방법)"
    r"|제출\s*서류|신청\s*방법|접수\s*방법|지원\s*내용|지원\s*규모|사업\s*목적"
)


def heading(source):
    return (
        source.group_label.split("[원문 구역]")[-1].splitlines()[-1].strip()
        if source.group_label
        else ""
    )


def recipient(source):
    context = source.group_label
    parent = re.search(r"\[상위 구역\]([^\n]*)", context)
    parent = parent[1] if parent else ""
    if "[별도 참여 역할] 구매기관" in context:
        return True
    return bool(
        re.search(r"(?:주관|운영|수행)\s*기관.{0,30}모집", context)
        and re.search(r"창업기업.{0,45}별도.{0,15}공고", context)
        and re.sub(r"\s+", "", parent) in {"사업개요", "지원사업개요"}
        and re.search(r"지원\s*대상.*(?:기업|업력)", source.excerpt)
    )


def boundary(line):
    value = line.strip()
    if value in {"[표]", "[/표]"} or not value or len(value) > 180:
        return False
    # Body lines, numbered alternatives and table rows are not section boundaries.
    return bool(re.match(r"^(?:[□■]|[가-하][.)])\s*", value)) or bool(
        re.match(r"^[\ue000-\uf8ff\U000f0000-\U000ffffd]\s*", value)
        and len(value) < 60
    ) or bool(
        len(value) < 60
        and re.match(r"^(?:선정|신청|지원|참여)\s*(?:대상|자격|기준|방법|절차)", value)
    )


def qualification_heading(line):
    title = re.sub(r"^[□■\ue000-\uf8ff\U000f0000-\U000ffffd]\s*", "", line)
    title = re.sub(r"^(?:[가-하][.)]|[0-9]+[.)])\s*", "", title)
    title = re.sub(r"^\(?과제별\s*", "", title).lstrip("(")
    return bool(QUALIFICATION.match(title)) and not IRRELEVANT.search(title)


def prepare(source, source_fields):
    if recipient(source):
        return None
    # Find real nested qualification sections in a coarse parsed block; do not cut inside tables.
    lines = source.excerpt.splitlines(keepends=True)
    starts, offset, table = [], 0, False
    for line in lines:
        if not table and boundary(line):
            starts.append(offset)
        if line.strip() == "[표]":
            table = True
        elif line.strip() == "[/표]":
            table = False
        offset += len(line)
    starts = sorted({0, *starts, len(source.excerpt)})
    chunks = [source.excerpt[a:b] for a, b in zip(starts, starts[1:]) if a < b]
    qualified = []
    for chunk in chunks:
        first = chunk.strip().splitlines()[0] if chunk.strip() else ""
        if qualification_heading(first):
            qualified.append(chunk)
    if qualified:
        excerpt = "\n[다음 원문 구간]\n".join(qualified)
        source = source.model_copy(update={"excerpt": excerpt})
        priority = 0
    elif qualification_heading(heading(source)):
        priority = 0
    elif IRRELEVANT.search(heading(source)):
        return None
    elif PREFERENCE.search(heading(source)) or re.search(
        r"제외\s*대상|신청\s*제외", heading(source)
    ):
        priority = 1
    else:
        priority = 2
    if not source_fields(source):
        return None
    return source, priority


def select(request, source_fields, codes, max_bytes, max_sources):
    candidates = []
    seen = set()
    for index, original in enumerate(request.sources):
        prepared = prepare(original, source_fields)
        if prepared is None:
            continue
        source, priority = prepared
        size = len(source.excerpt.encode("utf-8"))
        if not source.excerpt.strip() or size > max_bytes:
            continue
        # Keep different scopes and all qualifiers; deduplicate formatting variants only.
        scope = re.search(r"\[적용 유형\]([^\n]*)", source.group_label)
        identity = (
            re.sub(r"\s+|[｢｣「」『』]", "", source.excerpt),
            scope[1].strip() if scope else "",
        )
        if identity in seen:
            continue
        seen.add(identity)
        candidates.append((priority, index, source, size))
    selected, used = {}, 0
    # Exhaust actual qualification sections before filling capacity with any other topic.
    priorities = (0, 1) if any(rank == 0 for rank, *_ in candidates) else (1, 2)
    for priority in priorities:
        queues = {code: [] for code in codes}
        for rank, index, source, size in candidates:
            if rank == priority:
                for code in source_fields(source):
                    queues[code].append((index, source, size))
        while any(queues.values()) and len(selected) < max_sources:
            for code in codes:
                if not queues[code] or len(selected) >= max_sources:
                    continue
                index, source, size = queues[code].pop(0)
                if index not in selected and used + size <= max_bytes:
                    selected[index] = source
                    used += size
    return [source for _, source in sorted(selected.items())]
