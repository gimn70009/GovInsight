"""Bounded, dependency-free table text shared by the document readers.

Tabs separate cells; newlines separate physical rows. Only explicit
merges repeat their anchor text. A blank cell never inherits its neighbour.
"""

import re
from dataclasses import dataclass

TABLE_START = "[표]"
TABLE_END = "[/표]"
MAX_TABLE_CELLS = 100_000
MAX_TABLE_COLUMNS = 256
MAX_TABLE_ROWS = 10_000


@dataclass(frozen=True)
class Cell:
    row: int
    column: int
    text: str
    row_span: int = 1
    column_span: int = 1


def render_table(cells: list[Cell]) -> str | None:
    if not cells:
        return None
    rows = max(c.row + c.row_span for c in cells)
    columns = max(c.column + c.column_span for c in cells)
    if not (1 < rows <= MAX_TABLE_ROWS and 1 < columns <= MAX_TABLE_COLUMNS):
        return None
    if rows * columns > MAX_TABLE_CELLS:
        return None
    if sum(len(c.text) * c.row_span * c.column_span for c in cells) > 2_000_000:
        return None
    occupied: set[tuple[int, int]] = set()
    grid = [[""] * columns for _ in range(rows)]
    for cell in cells:
        if min(cell.row, cell.column) < 0 or min(cell.row_span, cell.column_span) < 1:
            return None
        text = re.sub(r"\s+", " ", cell.text).strip()
        for row in range(cell.row, cell.row + cell.row_span):
            for column in range(cell.column, cell.column + cell.column_span):
                if (row, column) in occupied:
                    return None
                occupied.add((row, column))
                grid[row][column] = text
    if not any(any(row) for row in grid):
        return None
    return "\n".join([TABLE_START, *("\t".join(row) for row in grid), TABLE_END])


def without_tables(text: str) -> str:
    # Also exclude an incomplete final table: it may have lost an exemption.
    return re.sub(r"(?ms)^\[표\]\r?\n.*?(?:^\[/표\][^\S\n]*(?:\n|$)|\Z)", "\n", text)


def tables(text: str):
    for match in re.finditer(r"(?ms)^\[표\]\r?\n(.*?)^\[/표\][^\S\n]*(?:\n|$)", text):
        rows = [line.split("\t") for line in match[1].splitlines()]
        if rows and len({len(row) for row in rows}) == 1 and len(rows[0]) > 1:
            yield match.start(), match.end(), rows
