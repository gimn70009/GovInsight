"""Validate visual transcription before adapting it to the shared table format."""

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.domains.monitoring.parsers.table_text import Cell, render_table


class OcrCell(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    row: int = Field(ge=0, lt=200)
    column: int = Field(ge=0, lt=32)
    row_span: int = Field(ge=1, le=200)
    column_span: int = Field(ge=1, le=32)
    text: str = Field(max_length=8000)


class OcrBlock(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    kind: Literal["text", "table"]
    text: str = Field(max_length=16000)
    cells: list[OcrCell] = Field(max_length=1000)


class OcrPage(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    complete: bool
    blocks: list[OcrBlock] = Field(max_length=100)

    def render(self) -> str:
        if not self.complete:
            raise ValueError("incomplete transcription")
        result = []
        for block in self.blocks:
            if block.kind == "text":
                if block.cells or not block.text.strip():
                    raise ValueError("invalid text block")
                text = _safe_text(block.text)
                result.append(text)
                continue
            if block.text or not block.cells:
                raise ValueError("invalid table block")
            cells = [Cell(**{**cell.model_dump(), "text": _safe_text(cell.text)})
                     for cell in block.cells]
            rows = max(c.row + c.row_span for c in cells)
            columns = max(c.column + c.column_span for c in cells)
            if rows > 200 or columns > 32:
                raise ValueError("table too large")
            table = render_table(cells)
            # Require explicit empty cells as well: omitted slots are not known blanks.
            if not table or sum(c.row_span * c.column_span for c in cells) != rows * columns:
                raise ValueError("incomplete or overlapping table")
            result.append(table)
        text = "\n\n".join(result)
        if not text.strip() or len(text) > 40000:
            raise ValueError("empty or oversized transcription")
        return text


def _safe_text(value: str) -> str:
    # Document content cannot forge the parser's structural delimiters.
    if re.search(r"\[/?표\]|\[파일:", value):
        raise ValueError("reserved document marker")
    return value.replace("\r\n", "\n").replace("\r", "\n").replace("\t", " ")
