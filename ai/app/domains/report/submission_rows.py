"""Recover bounded 구분/제출항목/설명 tables, including non-document deliverables."""

import re
from dataclasses import dataclass

from app.domains.analysis.proposals.submission_evidence import submission_section_contains

_HEADER = re.compile(r"구분\s*제출\s*항목\s*설명")
_STOP = re.compile(r"(?m)^\s*(?:※|\[일부 원문 생략|\[파일:|[□■]|\d+[.)]\s*(?:문의|유의))")
_MARKER = re.compile(r"(?m)^[ \t]*(필수|선택)(?=\s|[가-힣A-Za-z])\s*")
_ALTERNATIVE = re.compile(r"^(?:웹\s*서비스|앱|PC\s*프로그램)\s*:", re.I)
_DOCUMENT = re.compile(
    r"^(.{0,65}?(?:보고서|신청서|계획서|동의서|확약서|확인서|증명서)\s*\d+\s*부\.?)"
)
_VIDEO = re.compile(r"^(.{0,35}?(?:시연|데모)\s*(?:시연\s*)?영상)")


@dataclass(frozen=True)
class SubmissionItemRow:
    title: str
    detail: str
    quote: str
    level: str


def _cells(value: str, *, separate_cells: bool) -> tuple[str, str] | None:
    lines = [line.strip() for line in value.splitlines() if line.strip()]
    if not lines:
        return None
    if _ALTERNATIVE.match(lines[0]):
        alternatives = []
        for line in lines:
            if not _ALTERNATIVE.match(line):
                break
            alternatives.append(line)
        # Preserve alternatives together; they describe one type-dependent deliverable.
        return " · ".join(alternatives), " ".join(lines[len(alternatives):])
    first = lines[0]
    match = _DOCUMENT.match(first) or _VIDEO.match(first)
    if match:
        return match[1].strip(), " ".join([first[match.end():].strip(), *lines[1:]]).strip()
    # Cell-per-line extraction provides a real title/description boundary.
    # Inline PDF text without a known boundary is left to the model.
    if separate_cells and len(lines) >= 2 and len(first) <= 150:
        return first, " ".join(lines[1:])
    return None


def submission_item_rows(text: str) -> list[SubmissionItemRow]:
    # HWPX exposes the same table once flattened and once cell-by-cell. Remove
    # only a byte-for-byte equivalent (ignoring whitespace) flattened copy;
    # its example-result wording must not become a reference-section ancestor.
    duplicates = []
    for match in re.finditer(r"(?m)^구분제출항목설명[^\n]+\n", text):
        repeated = _HEADER.match(text, match.end())
        if repeated is None:
            continue
        stop = _STOP.search(text, repeated.end())
        if stop and re.sub(r"\s+", "", match[0]) == re.sub(
            r"\s+", "", text[repeated.start():stop.start()]
        ):
            duplicates.append((match.start(), match.end()))
    for start, end in reversed(duplicates):
        text = text[:start] + text[end:]
    result = []
    headers = list(_HEADER.finditer(text))
    for index, header in enumerate(headers):
        if not submission_section_contains(header[0], text[:header.end()]):
            continue
        boundary = _STOP.search(text, header.end())
        end = min(
            boundary.start() if boundary else len(text),
            headers[index + 1].start() if index + 1 < len(headers) else len(text),
        )
        if end - header.end() > 4000:
            continue
        table = text[header.end():end]
        starts = []
        for marker in _MARKER.finditer(table):
            start = marker.start()
            # PDF may vertically center 필수 beside the second of three alternatives.
            previous = re.search(r"(?m)^웹\s*서비스\s*:[^\n]+\n[ \t]*$", table[:start])
            if previous:
                start = previous.start()
            starts.append((start, marker))
        for row_index, (start, marker) in enumerate(starts):
            stop = starts[row_index + 1][0] if row_index + 1 < len(starts) else len(table)
            if row_index == len(starts) - 1 and boundary and boundary[0].strip().startswith(
                ("[일부 원문 생략", "[파일:")
            ):
                continue
            quote = table[start:stop].strip()
            if not 5 <= len(quote) <= 300:
                continue
            value = table[start:marker.start()] + table[marker.end():stop]
            cells = _cells(value, separate_cells="\n" in marker[0])
            if cells is None:
                continue
            title, detail = cells
            if not 2 <= len(title) <= 150 or not detail and not _ALTERNATIVE.match(title):
                continue
            # Unknown conditions/exemptions must not become unconditional requirements.
            if re.search(r"해당\s*시|경우|한하여|불필요|면제|미제출|제출하지", value):
                continue
            result.append(SubmissionItemRow(
                title, detail, quote, "MANDATORY" if marker[1] == "필수" else "OPTIONAL",
            ))
    return result
