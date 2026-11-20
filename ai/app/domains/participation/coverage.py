"""Account for original requirement spans without generating replacement facts.

Coverage and correctness are separate: an omitted requirement makes the response
partial, but cannot invalidate another supported statement from the same source.
"""

import re
from typing import NamedTuple

from . import completeness, requirements, selection


class Requirement(NamedTuple):
    ref: str
    text: str
    table_row: bool = False


BULLET = re.compile(r"^\s*(?:[◦○●•ㅇ□■❶-❿①-⑳※*]|[-–]\s|[\ue000-\uf8ff\U000f0000-\U000ffffd])")


def inventory(source):
    """Keep independent role/condition paragraphs and explicit qualification rows.

    These are review candidates, not assertions that every retained span applies.
    A model can leave an ambiguous source unresolved instead of silently skipping it.
    """
    rows = completeness.qualification_rows(source)
    result = [Requirement(ref, text, "\t" in text) for ref, _, text in rows]
    lines = source.excerpt.splitlines()
    qualification = selection.qualification_heading(selection.heading(source))
    applicant = False
    table = False
    start = None

    def flush(end):
        nonlocal start
        if start is None:
            return
        text = "\n".join(lines[start:end]).strip()
        first = text.splitlines()[0] if text else ""
        title = re.sub(r"^[\s□■◦○●•ㅇ*※-]+", "", first)
        if text and not (len(text) < 80 and (
            selection.QUALIFICATION.fullmatch(title)
            or re.fullmatch(r"(?:대상\s*기관|지원\s*조건)\s*[:：]?", title)
            or text.startswith("<") or re.fullmatch(r"\d+", text)
        )):
            explicit = (requirements.condition_context(text)
                        or selection.applicant_labels(text)
                        or selection.APPLICANT_EXCLUSION.search(text))
            inherited = (qualification or applicant) and not (
                selection.IRRELEVANT.search(title)
                or requirements.PROCEDURE.search(text)
                or re.match(r"^[*※]\s*(?:예\s*[:：)]|󰃚)", text)
            )
            if explicit or inherited:
                ref = f"L{start + 1}" if end == start + 1 else f"L{start + 1}-L{end}"
                result.append(Requirement(ref, text))
        start = None

    for index, line in enumerate(lines):
        value = line.strip()
        if value in {"[표]", "[/표]", "[다음 원문 구간]"}:
            flush(index)
            table = value == "[표]"
            if value == "[다음 원문 구간]":
                qualification = applicant = False
            continue
        if table:
            continue
        if BULLET.match(line) or selection.boundary(line) or not value:
            flush(index)
        if selection.boundary(line) or requirements.SECTION.fullmatch(value):
            qualification = selection.qualification_heading(value)
            applicant = False
        if re.match(r"^\s*[◦○●•ㅇ]", line):
            applicant = bool(selection.applicant_labels(line)) or bool(
                re.search(r"대상\s*기관", line)
            )
        title = re.sub(r"^[\s□■◦○●•ㅇ*※-]+", "", value)
        if selection.QUALIFICATION.fullmatch(title) or requirements.SECTION.fullmatch(value):
            flush(index)
            continue
        if value and start is None:
            start = index
    flush(len(lines))
    return sorted({unit.ref: unit for unit in result}.values(),
                  key=lambda u: int(u.ref[1:].split('-')[0]))


def normalized(value):
    value = completeness.compact(value).replace("⏎", "")
    return re.sub(r"(?<=\d)[명인]", "인", value)


def row_in_prose(row, quote):
    """Only accept an alternative citation when every nonempty cell is explicit.

    This is deliberately stricter than semantic similarity. A count-only summary
    cannot cover a row that also specifies experience or a legal qualification.
    """
    cells = [re.sub(r"^[\s·•]+", "", c).strip() for c in row.split("\t")]
    cells = [c for c in cells if c not in {"", "-", "요건"}]
    return bool(cells) and all(normalized(c) in normalized(quote) for c in cells)


def cites(unit, quotes):
    return any(normalized(unit.text) in normalized(q)
               or (unit.table_row and row_in_prose(unit.text, q)) for q in quotes)


def role_preserved(unit, text):
    if not unit.table_row:
        return True
    role = unit.text.split("\t")[0]
    role = re.sub(r"\([^)]*\)|[\ue000-\uf8ff\U000f0000-\U000ffffd]|⏎", "", role).strip()
    if role in {"요건", "구분", "인력", "확보 규모", "업무구분", "고용형태", "근무경력"}:
        return True
    return normalized(role) in normalized(text)


def covered(unit, source_id, statements):
    for statement in statements:
        body = source_id in statement.source_ids and not statement.review_required
        details = [d for d in statement.details
                   if not d.review_required and source_id in d.source_ids]
        quotes = ([*statement.supporting_quotes] if body else [])
        quotes += [q for d in details for q in d.supporting_quotes]
        # Separate, valid citations can together cover a paragraph's main condition,
        # definition and exception. They must all belong to this source and statement.
        if not cites(unit, [*quotes, "\n".join(dict.fromkeys(quotes))]):
            continue
        texts = ([statement.text] if body else []) + [d.text for d in details]
        if (completeness.row_values_preserved(unit.text, texts)
                and role_preserved(unit, " ".join(texts))):
            return True
    return False


def quantity_review(text, quotes, units):
    """Review only a claim quoting one explicit row, never its source siblings."""
    owned = [u for u in units if u.table_row and cites(u, quotes)]
    return len(owned) == 1 and not completeness.row_values_preserved(owned[0].text, text)


def audit(selected, statements):
    missing = {}
    for source in selected:
        units = inventory(source)
        uncovered = [unit for unit in units if not covered(unit, source.id, statements)]
        if uncovered:
            missing[source.id] = uncovered
    return missing
