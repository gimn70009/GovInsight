"""Source-structure checks; never infer new qualifications or rewrite a legal condition."""

import re
from collections import Counter


def compact(value):
    return re.sub(r"\s+|[「」｢｣『』\"“”]", "", value or "").replace("ㆍ", "·")


LAW = re.compile(r"[「｢『]([^」｣』]+)[」｣』](?:\s*(제\s*\d+\s*조(?:의\s*\d+)?))?")
EFFECT = re.compile(r"협약\s*(?:해약|해지)|선정\s*취소|참여\s*제한|제재|환수")


def legal_references(quote):
    """Extract named authorities and optional articles without inferring equivalents."""
    return list(dict.fromkeys(compact(m.group(0)) for m in LAW.finditer(quote)))


def expanded_law_aliases(original, texts):
    """Resolve a decree/rule shorthand only with one explicitly named parent law.

    Article numbers remain literal. A different law or ambiguous parent is never
    treated as equivalent merely because both citations say '시행령'.
    """
    displayed = re.sub(r'[「」｢｣『』"“”]', "", prose(texts))
    names = {compact(m[1]) for m in LAW.finditer(original)}
    parents = {re.sub(r"(?:시행령|시행규칙)$", "", name) for name in names}
    if len(parents) != 1:
        return compact(displayed)
    parent = next(iter(parents))
    parent_name = r"\s*".join(re.escape(char) for char in parent)
    if not re.search(r"(?<![가-힣])" + parent_name + r"(?=\s|제|의|$)", displayed):
        return compact(displayed)
    for suffix in ("시행령", "시행규칙"):
        if parent + suffix in names:
            displayed = re.sub(
                r"(?<![가-힣])(?:(?:같은\s*법|동법)\s*)?" + suffix + r"(?=\s*제\s*\d+\s*조)",
                parent + suffix, displayed,
            )
    return compact(displayed)


def laws_preserved(original, texts):
    displayed = expanded_law_aliases(original, texts)
    return all(law in displayed for law in legal_references(original))


def exception_missing(text, evidence):
    # A cap in the visible body must not silently apply to its explicitly exempt group.
    exceptions = re.split(
        r"(?:^|\n|⏎|(?<=[.!?])\s*)\s*[-*※]?\s*(?:다만[,，]?|단[,，])\s*", evidence
    )
    exemption = r"(?:제한|한도)\s*(?:이\s*)?없|면제|예외|적용하지\s*않"
    return bool(len(exceptions) > 1 and quantity_bounds(text)
                and re.search(exemption, "\n".join(exceptions[1:]))
                and not re.search(exemption, text))


def review_reason(text, details, quotes, kind):
    evidence = "\n".join(quotes)
    if exception_missing(text, evidence):
        return "EXCEPTION_NOT_IN_MAIN"
    # Do not require every law in a broad table citation: only a single applicant row,
    # or a standalone legal list, is unambiguously owned by this condition.
    rows = {line for line in evidence.splitlines() if "\t" in line}
    if kind == "ELIGIBILITY" and len(rows) <= 1:
        laws = legal_references(evidence)
        if len(laws) >= 2 and not laws_preserved(evidence, [text, *details]):
            return "LEGAL_SCOPE_INCOMPLETE"
    if kind == "EXCLUSION":
        claimed = {compact(m.group()) for m in EFFECT.finditer(text)}
        supported = {compact(m.group()) for m in EFFECT.finditer(evidence)}
        if claimed - supported:
            return "OUTCOME_NOT_IN_CITATION"
        # A side note's consequences may not be shared with the preceding independent
        # exclusion clause. Keep a compound source unresolved rather than guessing.
        parts = re.split(r"\n\s*[*※]\s*", evidence)
        if len(parts) > 1 and re.search(r"거나|또는", text):
            main_effects = {compact(m.group()) for m in EFFECT.finditer(parts[0])}
            note_effects = {compact(m.group()) for m in EFFECT.finditer("\n".join(parts[1:]))}
            if claimed & (note_effects - main_effects):
                return "OUTCOME_SCOPE_MERGED"
    return None


# Count units are interchangeable in Korean prose; amount/time units are not.
QUANTITY = re.compile(
    r"(?<![A-Za-z0-9])([0-9]+(?:\.[0-9]+)?)\s*(%|개사|개월|명|인|개|곳|년|일|석|팀|점)(?![A-Za-z])"
)
BOUND = re.compile(r"\s*(이상|이하|미만|초과|이내)")
BOUND_NAMES = {"최소": "이상", "최대": "이하", "이내": "이하"}


def distinct_cells(value):
    return "\n".join("\t".join(dict.fromkeys(line.split("\t"))) for line in value.splitlines())


def prose(texts):
    return texts if isinstance(texts, str) else "\n".join(dict.fromkeys(texts))


def quantities(value):
    return Counter((number, "count" if unit in {"명", "인"} else unit)
                   for number, unit in QUANTITY.findall(distinct_cells(value)))


def quantity_bounds(value):
    result = Counter()
    value = distinct_cells(value)
    for match in QUANTITY.finditer(value):
        bound = BOUND.match(value[match.end():])
        prefix = re.search(r"(최소|최대)\s*$", value[:match.start()])
        relation = bound[1] if bound else prefix[1] if prefix else None
        if relation:
            result[(match[1], "count" if match[2] in {"명", "인"} else match[2],
                    BOUND_NAMES.get(relation, relation))] += 1
    return result


def counted_targets(value):
    """Bind forward count phrases to their named objects, e.g. 2개 이상의 광역지자체."""
    result = []
    pattern = (r"([0-9]+(?:\.[0-9]+)?)\s*(개사|개|곳)\s*"
               r"(?:이상|이하|미만|초과|이내)\s*의\s*([가-힣·]+)")
    for number, unit, noun in re.findall(pattern, distinct_cells(value)):
        noun = re.sub(r"(?:에는|에서|으로|에|을|를|이|가|은|는|와|과)$", "", noun)
        if len(noun) >= 2:
            result.append((number, unit, noun))
    return result


def row_values_preserved(row, texts):
    displayed = prose(texts)
    if not (quantities(row) <= quantities(displayed)
            and quantity_bounds(row) <= quantity_bounds(displayed)):
        return False
    for number, unit, noun in counted_targets(row):
        # Preserve a counted object's identity even when the sentence reverses word order.
        def count_spelling(value):
            return value.replace("산업단지", "산단").replace("지방자치단체", "지자체")

        target = re.escape(count_spelling(noun))
        amount = re.escape(number) + r"\s*" + re.escape(unit)
        forward = amount + r"\s*(?:(?:이상|이하|미만|초과|이내)\s*)?의?\s*" + target
        backward = target + r"(?:은|는|이|가)?\s*(?:(?:최소|최대)\s*)?" + amount
        if not re.search(forward + "|" + backward, count_spelling(displayed)):
            return False
    return True


def required_terms_preserved(original, texts):
    """Check explicit mandatory participants and scope anchors, not arbitrary word overlap."""
    displayed = prose(texts)
    if not laws_preserved(original, texts):
        return False
    clean = re.sub(r"\([^)]*\)", "", original)
    for noun in re.findall(r"([가-힣·]+)\s*(?:\([^)]*\))?\s*중심(?:으로)?", clean):
        if compact(noun) not in compact(displayed):
            return False
    for noun in re.findall(r"([가-힣·]+)\s*필수\s*(?:참여|포함|가입)", original):
        noun = re.sub(r"(?:의|은|는|이|가)$", "", noun)
        if noun in {"이상", "이하", "이내"}:
            continue
        clauses = [part for part in re.split(r"[.!?。\n]", displayed) if noun in compact(part)]
        if not any(re.search(r"필수|의무|반드시|해야|하여야", part)
                   and not re.search(r"필수(?:가)?\s*아니|필요\s*없", part) for part in clauses):
            return False
    return True


def qualification_rows(source):
    """Explicit role/requirement tables, including merged headers and personnel columns."""
    lines = source.excerpt.splitlines()
    in_table = False
    is_qualification = False
    first_row = False
    context = False
    applicant_block = False
    result = []
    headers = {"자격", "자격기준", "신청자격", "자격요건", "요건", "조건", "설립근거등", "설립근거"}
    for number, line in enumerate(lines, 1):
        if line.strip() == "[표]":
            in_table, is_qualification, first_row = True, False, True
            nearby = source.group_label + "\n" + "\n".join(lines[max(0, number - 5):number - 1])
            context = bool(re.search(
                r"신청\s*(?:요건|자격)|인력\s*구성|전담\s*인력|자격\s*기준", nearby
            ))
            continue
        if line.strip() == "[/표]":
            in_table, is_qualification = False, False
            continue
        if not in_table and re.match(
            r"^[\s◦○●•ㅇ]*(?:대상\s*기관|(?:신청|지원|참여)\s*대상)\s*[:：]?$", line
        ):
            applicant_block = True
        elif not in_table and re.match(r"^[\s]*[◦○●•ㅇ□■]", line):
            applicant_block = False
        if not in_table and applicant_block and re.match(r"^\s*-\s*\S", line):
            result.append((f"L{number}", "신청 대상", line))
        cells = line.split("\t")
        if not in_table or len(cells) < 2:
            continue
        normalized = [compact(cell) for cell in cells]
        if first_row:
            first_row = False
            is_qualification = bool(set(normalized) & headers) or (
                context and "주요내용" in normalized and "구분" in normalized
            )
            continue
        if any(re.fullmatch(r"(?:가점|우대)(?:항목|대상|사항)?", cell) for cell in normalized):
            result.append((f"L{number}", cells[0].strip(), line))
            continue
        if not is_qualification or set(normalized) & headers:
            # Repeated headers are not requirements; a row labelled 요건 still is.
            if not (is_qualification and "요건" in normalized and any(len(c) > 8 for c in cells)):
                continue
        if "개요" in normalized or not all(cell.strip() for cell in cells):
            continue
        result.append((f"L{number}", cells[0].strip(), line))
    return result
