"""Bounded structural selection. No model calls, notice names, amounts or generated facts."""

import re

QUALIFICATION = re.compile(
    r"(?:사업\s*)?(?:(?:신청|참여|모집|기업)\s*(?:자격|대상|요건|조건)"
    r"|지원\s*(?:대상|자격|요건)|선정\s*대상|자격\s*요건|입찰\s*참가\s*자격)"
    r"(?=\s|[:：()（）·ㆍ]|$)"
)
EXCLUSION_HEADING = re.compile(r"(?:지원|신청|참여)\s*제외|제외\s*대상")
# Preserve explicit applicant exclusions even when nested under general precautions.
APPLICANT_EXCLUSION = re.compile(
    r"(?:기업|기관|사업자|대표자|참여하는\s*자)[^\n]{0,100}"
    r"(?:지원\s*대상에서\s*제외|지원\s*제외|신청(?:이|할\s*수)?\s*(?:불가|없))"
)
PREFERENCE = re.compile(r"우대|가점|가산점")
EXTRACTION_BOUNDARY = re.compile(
    r"\[(?:PDF\s+\d+페이지\s+시각\s*판독|문서\s*전체\s*텍스트\s*추출)\]"
)
# Input context only: the model decides whether these are current applicant requirements.
PARTICIPATION_CONTEXT = re.compile(
    r"참여\s*(?:기업|기관|업체)\s*(?:중|에\s*한)"
    r"|(?:기|이미)\s*선정[^\n]{0,60}(?:계속|수행)"
)
SELECTION_OUTCOME = re.compile(r"(?:선정|모집)\s*규모|최종\s*선정")
IRRELEVANT = re.compile(
    r"(?:선정|협약)\s*후|유의\s*사항|문의|주요\s*용어|정의|향후\s*절차|평가\s*(?:절차|내용|방법)"
    r"|제출\s*서류|신청\s*방법|접수\s*방법|지원\s*내용|지원\s*규모|사업\s*목적"
    r"|입찰\s*보증금|과업\s*수행\s*계획|주요\s*과업|평가\s*항목|성과\s*보고|계약\s*체결\s*후"
    r"|지원\s*(?:기간|한도|비율|예산|과제\s*수)|선정\s*(?:규모|기준|절차|방법)"
    r"|모집\s*규모|최종\s*선정|지원\s*범위|신청서\s*제출\s*전"
    r"|추진\s*체계|관련\s*규정|운영\s*규정|신청\s*절차|접수\s*기간"
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
    if EXTRACTION_BOUNDARY.fullmatch(value):
        return True
    if EXCLUSION_HEADING.match(re.sub(r"^[\s□■◦○●•ㅇ*-]+", "", value)):
        return True
    if value in {"[표]", "[/표]"} or not value or len(value) > 180:
        return False
    # Body lines, numbered alternatives and table rows are not section boundaries.
    return (
        bool(re.match(r"^(?:[□■]|[가-하][.)])\s*", value))
        or bool(
            re.match(r"^(?:[0-9]+(?:-[0-9]+)+[.]?|[\ue000-\uf8ff\U000f0000-\U000ffffd])\s*", value)
            and len(value) < 60
        )
        or bool(
            len(value) < 60
            and re.match(r"^(?:선정|신청|지원|참여)\s*(?:대상|자격|기준|방법|절차)", value)
        )
    )


def qualification_heading(line):
    title = re.sub(r"^[□■\ue000-\uf8ff\U000f0000-\U000ffffd]\s*", "", line)
    title = re.sub(r"^(?:[가-하][.)]|[0-9]+(?:[-.][0-9]+)*[.)]?)\s*", "", title)
    title = re.sub(r"^\(?과제별\s*", "", title).lstrip("(")
    return bool(QUALIFICATION.match(title)) and not IRRELEVANT.search(title)


def applicant_labels(text, *, table_only=False):
    """Retain explicit target rows/columns inside mixed support tables for model interpretation."""
    label = r"(?:사업\s*)?(?:신청|참여|모집|지원)\s*(?:대상|자격|요건)"
    for line in text.splitlines():
        if "\t" in line and any(re.fullmatch(label, cell.strip()) for cell in line.split("\t")):
            return True
        if not table_only and re.match(
            r"^[\s◦○●•ㅇ*\-]*(?:\(\s*" + label + r"\s*\)|" + label + r"(?:\s*[:：]|\s*$))", line
        ):
            return True
    return False


def bullet_title(line):
    match = re.match(r"^\s*[◦○●•ㅇ]\s*(?:\(([^)]+)\)|([^:：\n]+))", line)
    return (match[1] or match[2]).strip() if match else ""


def applicant_span(chunk):
    """Keep a labelled applicant paragraph and its nested roles/notes, verbatim."""
    if "[표]" in chunk or "\t" in chunk:
        return chunk
    lines = chunk.splitlines(keepends=True)
    spans, offset, start = [], 0, None
    for line in lines:
        labelled = applicant_labels(line)
        sibling_title = bullet_title(line)
        if start is not None and IRRELEVANT.fullmatch(sibling_title) and not labelled:
            spans.append(chunk[start:offset])
            start = None
        if labelled and start is None:
            if not spans:
                prefix = re.sub(r"\A\s*[□■]\s*사업\s*개요\s*\n", "", chunk[:offset])
                blocks = re.split(r"(?m)(?=^[ \t]*[◦○●•ㅇ]\s*)", prefix)
                # Unknown leading content may be a common condition. Never discard it.
                if any(block.strip() and not IRRELEVANT.fullmatch(bullet_title(block))
                       for block in blocks):
                    return chunk
            start = offset
        offset += len(line)
    if start is not None:
        spans.append(chunk[start:])
    return "\n[다음 원문 구간]\n".join(spans) if spans else chunk


def prepare(source, source_fields):
    if not source.excerpt.strip():
        return None
    if QUALIFICATION.fullmatch(source.excerpt.strip()):
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
    qualified, contextual, mixed = [], [], []
    for start, end in zip(starts, starts[1:]):
        chunk = source.excerpt[start:end]
        first = chunk.strip().splitlines()[0] if chunk.strip() else ""
        if qualification_heading(first) or EXCLUSION_HEADING.search(first):
            # Adjacent original sections stay adjacent; never insert a fake gap into an exact quote.
            if qualified and qualified[-1][1] == start:
                qualified[-1] = (qualified[-1][0], end)
            else:
                qualified.append((start, end))
        elif APPLICANT_EXCLUSION.search(chunk):
            contextual.append((start, end))
        elif (
            PARTICIPATION_CONTEXT.search(chunk)
            and not SELECTION_OUTCOME.search(heading(source) + "\n" + first)
        ):
            # Preserve the original restriction/continuation paragraph even under 지원내용.
            # Source adjacency has no attachment/offset guarantee, so never infer a shared scope.
            contextual.append((start, end))
        elif applicant_labels(chunk):
            mixed.append((start, end))
    if qualified or contextual or mixed:
        spans = []
        for start, end in sorted(qualified + contextual + mixed):
            chunk = source.excerpt[start:end]
            # Trim overview prose only; preserve every subordinate condition/exception.
            if (start, end) in mixed:
                chunk = applicant_span(chunk)
            if (spans and spans[-1][1] == start and chunk == source.excerpt[start:end]
                    and spans[-1][2] == source.excerpt[spans[-1][0]:start]):
                spans[-1] = (spans[-1][0], end, spans[-1][2] + chunk)
            else:
                spans.append((start, end, chunk))
        excerpt = "\n[다음 원문 구간]\n".join(chunk for _, _, chunk in spans)
        source = source.model_copy(update={"excerpt": excerpt})
        priority = 1 if qualified else 2 if contextual else 3
    elif any(EXTRACTION_BOUNDARY.fullmatch(line.strip()) for line in lines):
        # Extraction restarts sever inherited headings. Explicit sections above remain usable.
        return None
    elif IRRELEVANT.search(heading(source)):
        return None
    elif re.search(r"지원\s*조건", heading(source)):
        # A support-conditions heading is ambiguous. Keep field-bearing content at low priority,
        # without inheriting a parent eligibility heading; the model decides what is eligibility.
        priority = 4
    elif PREFERENCE.search(heading(source)) or re.search(
        r"제외\s*대상|신청\s*제외", heading(source)
    ):
        priority = 2
    elif qualification_heading(heading(source)) or (
        (parent := re.search(r"\[상위 구역\]([^\n]*)", source.group_label))
        and qualification_heading(parent[1].strip())
    ):
        priority = 1
    else:
        priority = 5
    # Direct applicant qualifications come before broad target tables and exclusion checks.
    direct = heading(source) + "\n" + source.excerpt.splitlines()[0]
    if priority <= 1 and re.search(r"신청\s*(?:자격|요건)|입찰\s*참가\s*자격|자격\s*요건", direct):
        priority = 0
    if priority > 3 and not source_fields(source):
        return None
    if recipient(source):
        # Preserve separate applicant roles for the model, after the direct recruitment.
        priority = 5
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
            source.group_label,
        )
        if identity in seen:
            continue
        seen.add(identity)
        candidates.append((priority, index, source, size))
    # This is input ordering only: never remove a condition because its extraction looks noisy.
    noisy = {
        index for _, index, _, _ in candidates
        if len(set(re.findall(r"([가-힣])\1", request.sources[index].excerpt))) >= 4
        or any(
            line.strip().startswith("[PDF ") and EXTRACTION_BOUNDARY.fullmatch(line.strip())
            for line in request.sources[index].excerpt.splitlines()
        )
    }

    selected, used = {}, 0
    # Exhaust actual qualification sections before filling capacity with any other topic.
    priorities = (0, 1, 2, 3, 4) if any(rank <= 4 for rank, *_ in candidates) else (5,)
    for priority in priorities:
        queues = {code: [] for code in (*codes, "_OTHER")}
        for rank, index, source, size in candidates:
            if rank == priority:
                for code in source_fields(source) or {"_OTHER"}:
                    queues[code].append((index, source, size))
        # Prefer normal body extraction, retaining visual evidence as a separate candidate.
        # Target tables precede overview labels, without merging different source scopes/facts.
        for queue in queues.values():
            queue.sort(key=lambda item: (
                item[0] in noisy,
                priority == 3 and not applicant_labels(item[1].excerpt, table_only=True),
                # Within mixed target blocks, explicit bounds/status precede bare target names.
                priority == 3 and not re.search(
                    r"이상|이하|미만|초과|이내|한하여|한해|중인", item[1].excerpt
                ),
                -min(len(item[1].excerpt.strip()), 600) if priority == 3 else 0,
            ))
        while any(queues.values()) and len(selected) < max_sources:
            for code in queues:
                if not queues[code] or len(selected) >= max_sources:
                    continue
                index, source, size = queues[code].pop(0)
                if index not in selected and used + size <= max_bytes:
                    selected[index] = source
                    used += size
    ordered = sorted(selected.items(), key=lambda item: (item[0] in noisy, item[0]))
    return [source for _, source in ordered]
