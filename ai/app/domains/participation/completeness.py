"""Source-structure checks; never infer new qualifications or rewrite a legal condition."""

import re


def compact(value):
    return re.sub(r"\s+|[「」｢｣『』\"“”]", "", value or "").replace("ㆍ", "·")


LAW = re.compile(r"[「｢『]([^」｣』]+)[」｣』](?:\s*(제\s*\d+\s*조(?:의\s*\d+)?))?")
EFFECT = re.compile(r"협약\s*(?:해약|해지)|선정\s*취소|참여\s*제한|제재|환수")


def legal_references(quote):
    """Exact named authorities are required only when the claim cites a qualification row."""
    return list(dict.fromkeys(compact(m.group(0)) for m in LAW.finditer(quote)))


def review_reason(text, details, quotes, kind):
    displayed = compact(" ".join([text, *details]))
    evidence = "\n".join(quotes)
    # Do not require every law in a broad table citation: only a single applicant row,
    # or a standalone legal list, is unambiguously owned by this condition.
    rows = {line for line in evidence.splitlines() if "\t" in line}
    if kind == "ELIGIBILITY" and len(rows) <= 1:
        laws = legal_references(evidence)
        if len(laws) >= 2 and any(law not in displayed for law in laws):
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
    r"(?<![A-Za-z0-9])([0-9]+(?:\.[0-9]+)?)\s*(%|개사|개월|명|인|개|곳|년|일|석|팀)(?![A-Za-z])"
)


def quantities(value):
    return {(number, "count" if unit in {"명", "인"} else unit)
            for number, unit in QUANTITY.findall(value)}


def row_values_preserved(row, texts):
    return quantities(row) <= quantities(" ".join(texts))


def qualification_rows(source):
    """Explicit role/requirement tables, including merged headers and personnel columns."""
    lines = source.excerpt.splitlines()
    in_table = False
    is_qualification = False
    first_row = False
    context = False
    applicant_block = False
    result = []
    headers = {"자격", "자격기준", "신청자격", "자격요건", "요건", "조건"}
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
        if not is_qualification or set(normalized) & headers:
            # Repeated headers are not requirements; a row labelled 요건 still is.
            if not (is_qualification and "요건" in normalized and any(len(c) > 8 for c in cells)):
                continue
        if "개요" in normalized or not all(cell.strip() for cell in cells):
            continue
        result.append((f"L{number}", cells[0].strip(), line))
    return result
