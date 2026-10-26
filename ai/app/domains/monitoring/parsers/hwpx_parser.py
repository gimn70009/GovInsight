import re
import zipfile
from pathlib import Path
from xml.etree import ElementTree

from app.domains.monitoring.parsers.base import AttachmentParseError, ParsedAttachment
from app.domains.monitoring.parsers.table_text import Cell, render_table
from app.domains.monitoring.parsers.xml_package import (
    local_name,
    parse_xml,
    read_xml,
    text_blocks,
    validate_package,
)

SECTION_PATH = re.compile(r"^Contents/section(\d+)\.xml$", re.IGNORECASE)
MAX_SECTION_COUNT = 1_000
MAX_XML_SIZE_BYTES = 50 * 1024 * 1024


class HwpxParseError(AttachmentParseError):
    pass


class HwpxParser:
    def parse(self, file_path: Path) -> ParsedAttachment:
        try:
            with zipfile.ZipFile(file_path) as archive:
                validate_package(archive)
                paragraphs = []
                for entry in self._section_entries(archive):
                    paragraphs.extend(text_blocks(read_xml(archive, entry.filename), _table))
        except (zipfile.BadZipFile, OSError, ElementTree.ParseError, KeyError, RuntimeError) as exc:
            raise HwpxParseError("HWPX 파일을 읽을 수 없습니다.") from exc
        text = "\n".join(paragraphs).strip()
        if not text:
            raise HwpxParseError("HWPX 파일에서 텍스트를 찾지 못했습니다.")
        return ParsedAttachment(text=text)

    def _section_entries(self, archive):
        entries = []
        for entry in archive.infolist():
            match = SECTION_PATH.fullmatch(entry.filename.replace("\\", "/"))
            if match:
                entries.append((int(match[1]), entry))
        if not entries:
            raise HwpxParseError("HWPX 본문 XML을 찾지 못했습니다.")
        if len(entries) > MAX_SECTION_COUNT:
            raise HwpxParseError("HWPX 본문 섹션 수가 너무 많습니다.")
        return [entry for _, entry in sorted(entries, key=lambda item: item[0])]

    def _parse_section(self, xml_bytes: bytes) -> list[str]:
        return text_blocks(parse_xml(xml_bytes), _table)


def _table(node: ElementTree.Element) -> str | None:
    # Layout tables containing other tables retain their original paragraph order.
    if any(local_name(child.tag) == "tbl" for child in node.iter() if child is not node):
        return None
    cells = []
    try:
        for row_index, row in enumerate(c for c in node if local_name(c.tag) == "tr"):
            for column_index, cell in enumerate(c for c in row if local_name(c.tag) == "tc"):
                address = next((c for c in cell if local_name(c.tag) == "cellAddr"), None)
                span = next((c for c in cell if local_name(c.tag) == "cellSpan"), None)
                cells.append(Cell(
                    int(address.get("rowAddr", row_index)) if address is not None else row_index,
                    int(address.get("colAddr", column_index)) if address is not None
                    else column_index,
                    " ".join(text_blocks(cell, lambda _: None)),
                    int(span.get("rowSpan", "1")) if span is not None else 1,
                    int(span.get("colSpan", "1")) if span is not None else 1,
                ))
    except (TypeError, ValueError):
        return None
    return render_table(cells)
