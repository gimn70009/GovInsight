import zipfile
from dataclasses import replace
from pathlib import Path
from xml.etree import ElementTree

from app.domains.monitoring.parsers.base import AttachmentParseError, ParsedAttachment
from app.domains.monitoring.parsers.table_text import Cell, render_table
from app.domains.monitoring.parsers.xml_package import (
    attribute,
    local_name,
    read_xml,
    text_blocks,
    validate_package,
)


class DocxParser:
    def parse(self, file_path: Path) -> ParsedAttachment:
        try:
            with zipfile.ZipFile(file_path) as archive:
                validate_package(archive)
                root = read_xml(archive, "word/document.xml")
                text = "\n".join(text_blocks(root, _table)).strip()
        except (OSError, KeyError, RuntimeError, zipfile.BadZipFile, ElementTree.ParseError) as exc:
            raise AttachmentParseError("DOCX 파일을 읽을 수 없습니다.") from exc
        if not text:
            raise AttachmentParseError("DOCX 파일에서 텍스트를 찾지 못했습니다.")
        return ParsedAttachment(text=text)


def _property(node, group, name):
    # tcPrChange/trPrChange contain previous formatting, not the current grid.
    properties = next((child for child in node if local_name(child.tag) == group), None)
    if properties is None:
        return None
    return next((child for child in properties if local_name(child.tag) == name), None)


def _table(node: ElementTree.Element) -> str | None:
    if any(local_name(child.tag) == "tbl" for child in node.iter() if child is not node):
        return None
    cells: list[Cell] = []
    vertical: dict[tuple[int, int], int] = {}
    try:
        for row_index, row in enumerate(child for child in node if local_name(child.tag) == "tr"):
            before = _property(row, "trPr", "gridBefore")
            column = int(attribute(before, "val", "0")) if before is not None else 0
            continued = {}
            for cell in (child for child in row if local_name(child.tag) == "tc"):
                span = _property(cell, "tcPr", "gridSpan")
                width = int(attribute(span, "val", "1")) if span is not None else 1
                if width < 1:
                    return None
                # The older hMerge representation is left as paragraphs, not guessed.
                if _property(cell, "tcPr", "hMerge") is not None:
                    return None
                merge = _property(cell, "tcPr", "vMerge")
                value = " ".join(text_blocks(cell, lambda _: None))
                key = (column, width)
                if merge is not None and attribute(merge, "val") != "restart":
                    if key not in vertical or value:
                        return None
                    index = vertical[key]
                    cells[index] = replace(cells[index], row_span=cells[index].row_span + 1)
                    continued[key] = index
                else:
                    cells.append(Cell(row_index, column, value, column_span=width))
                    if merge is not None:
                        continued[key] = len(cells) - 1
                column += width
            vertical = continued
    except (ValueError, TypeError):
        return None
    return render_table(cells)
