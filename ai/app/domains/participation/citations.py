"""Address original lines, cells and delimited passages without rewriting evidence."""

import re

PATTERN = r"^L[1-9][0-9]*(?:-L[1-9][0-9]*|:C[1-9][0-9]*(?::P[1-9][0-9]*)?|:P[1-9][0-9]*)?$"
REFERENCE = re.compile(
    r"L([1-9][0-9]*)(?:-L([1-9][0-9]*)|:C([1-9][0-9]*)(?::P([1-9][0-9]*))?|:P([1-9][0-9]*))?"
)


def passages(value):
    # Parser cell line breaks and sentence boundaries retain contiguous original spans.
    return re.split(r"\s*⏎\s*|(?<=[.!?。])\s+", value)


def partial_reference(value):
    match = REFERENCE.fullmatch(value)
    return bool(match and any(match[index] for index in (3, 4, 5)))


def resolve(value, source):
    match = REFERENCE.fullmatch(value)
    if not match:
        legacy = re.fullmatch(r"(L[1-9][0-9]*(?:-L[1-9][0-9]*)?)\s*:\s+(.*)", value, re.DOTALL)
        if legacy:
            match = REFERENCE.fullmatch(legacy[1])
    if not match:
        # Legacy/mock verbatim citations still undergo original-span grounding.
        return value
    lines = source.excerpt.splitlines(keepends=True)
    first, last = int(match[1]), int(match[2] or match[1])
    if first > last or last > len(lines):
        return ""
    quote = "".join(lines[first - 1:last]).rstrip("\r\n")
    if match[3]:
        cells = quote.split("\t")
        cell = int(match[3])
        if cell > len(cells):
            return ""
        quote = cells[cell - 1]
    if match[4] or match[5]:
        parts = passages(quote)
        part = int(match[4] or match[5])
        if part > len(parts):
            return ""
        quote = parts[part - 1]
    return quote


def numbered(source):
    result = []
    for number, line in enumerate(source.excerpt.splitlines(), 1):
        ref = f"L{number}"
        if len(line) <= 600:
            result.append(f"{ref}: {line}")
            continue
        cells = line.split("\t")
        seen = set()
        for index, cell in enumerate(cells, 1):
            if cell in seen:
                continue
            seen.add(cell)
            cell_ref = f"{ref}:C{index}" if len(cells) > 1 else ref
            if len(cell) <= 600:
                result.append(f"{cell_ref}: {cell}")
            else:
                result.extend(f"{cell_ref}:P{i}: {part}"
                              for i, part in enumerate(passages(cell), 1))
    return "\n".join(result)
