import posixpath
import re
import zipfile
from dataclasses import replace
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from xml.etree import ElementTree

from app.domains.monitoring.parsers.base import AttachmentParseError, ParsedAttachment
from app.domains.monitoring.parsers.table_text import MAX_TABLE_CELLS, Cell, render_table
from app.domains.monitoring.parsers.xml_package import (
    attribute,
    local_name,
    read_xml,
    validate_package,
)

_ADDRESS = re.compile(r"^([A-Z]{1,3})([1-9]\d{0,6})$")
_DATE_FORMATS = {14, 15, 16, 17, 22, 27, 30, 36, 50, 57}


def _position(reference: str) -> tuple[int, int]:
    match = _ADDRESS.fullmatch(reference)
    if not match:
        raise AttachmentParseError("XLSX 셀 주소가 올바르지 않습니다.")
    column = 0
    for letter in match[1]:
        column = column * 26 + ord(letter) - 64
    return int(match[2]) - 1, column - 1


def _child(node, name):
    return next((child for child in node if local_name(child.tag) == name), None)


def _strings(node):
    # Phonetic/ruby annotations are not part of the displayed cell value.
    return "".join(
        child.text or ""
        for part in node
        if local_name(part.tag) != "rPh"
        for child in part.iter()
        if local_name(child.tag) == "t"
    )


class XlsxParser:
    def parse(self, file_path: Path) -> ParsedAttachment:
        try:
            with zipfile.ZipFile(file_path) as archive:
                validate_package(archive)
                workbook = read_xml(archive, "xl/workbook.xml")
                relations = read_xml(archive, "xl/_rels/workbook.xml.rels")
                targets = {}
                for relation in relations:
                    if relation.get("TargetMode") == "External":
                        continue
                    if not relation.get("Type", "").endswith("/worksheet"):
                        continue
                    target = relation.get("Target", "")
                    target = posixpath.normpath(
                        target.lstrip("/") if target.startswith("/") else "xl/" + target
                    )
                    if not target.startswith("xl/"):
                        raise AttachmentParseError("XLSX 시트 경로가 올바르지 않습니다.")
                    targets[relation.get("Id")] = target
                shared = []
                if "xl/sharedStrings.xml" in archive.namelist():
                    shared = [_strings(node) for node in read_xml(archive, "xl/sharedStrings.xml")]
                formats = []
                if "xl/styles.xml" in archive.namelist():
                    styles = read_xml(archive, "xl/styles.xml")
                    xfs = _child(styles, "cellXfs")
                    if xfs is not None:
                        formats = [int(node.get("numFmtId", "0")) for node in xfs]
                properties = _child(workbook, "workbookPr")
                epoch_1904 = properties is not None and properties.get("date1904") in ("1", "true")
                sections = []
                for sheet in workbook.iter():
                    if (
                        local_name(sheet.tag) != "sheet"
                        or sheet.get("state", "visible") != "visible"
                    ):
                        continue
                    target = targets.get(attribute(sheet, "id"))
                    if not target:
                        continue
                    root = read_xml(archive, target)
                    text = self._sheet(root, shared, formats, epoch_1904)
                    if text:
                        name = re.sub(r"\s+", " ", sheet.get("name", "시트"))
                        sections.append(f"[시트: {name}]\n{text}")
                text = "\n\n".join(sections)
        except (
            OSError,
            KeyError,
            RuntimeError,
            ValueError,
            IndexError,
            OverflowError,
            zipfile.BadZipFile,
            ElementTree.ParseError,
        ) as exc:
            raise AttachmentParseError("XLSX 파일을 읽을 수 없습니다.") from exc
        if not text:
            raise AttachmentParseError("XLSX 파일에서 텍스트를 찾지 못했습니다.")
        return ParsedAttachment(text=text)

    def _sheet(self, root, shared, formats, epoch_1904):
        cells = {}
        for node in root.iter():
            if local_name(node.tag) != "c":
                continue
            row, column = _position(node.get("r", ""))
            if (row, column) in cells:
                raise AttachmentParseError("XLSX 셀 주소가 중복되었습니다.")
            cells[row, column] = Cell(row, column, self._value(node, shared, formats, epoch_1904))
            if len(cells) > MAX_TABLE_CELLS:
                raise AttachmentParseError("XLSX 셀 수가 너무 많습니다.")
        for merge in root.iter():
            if local_name(merge.tag) != "mergeCell":
                continue
            ends = merge.get("ref", "").split(":")
            if len(ends) != 2:
                raise AttachmentParseError("XLSX 병합 범위가 올바르지 않습니다.")
            first, last = map(_position, ends)
            if last[0] < first[0] or last[1] < first[1]:
                raise AttachmentParseError("XLSX 병합 범위가 올바르지 않습니다.")
            anchor = cells.get(first, Cell(*first, ""))
            for address in list(cells):
                if address != first and all(first[i] <= address[i] <= last[i] for i in (0, 1)):
                    if cells[address].text:
                        raise AttachmentParseError("XLSX 병합 셀의 내용이 충돌합니다.")
                    del cells[address]
            cells[first] = replace(
                anchor,
                row_span=last[0] - first[0] + 1,
                column_span=last[1] - first[1] + 1,
            )
        if not cells or not any(cell.text for cell in cells.values()):
            return ""
        min_row = min(c.row for c in cells.values())
        min_column = min(c.column for c in cells.values())
        shifted = [
            replace(c, row=c.row - min_row, column=c.column - min_column) for c in cells.values()
        ]
        table = render_table(shifted)
        if table:
            return table
        # Keep coordinates on oversized/single-column sheets instead of inventing adjacency.
        return "\n".join(
            f"[행 {r + 1}, 열 {c + 1}] {cell.text}"
            for (r, c), cell in sorted(cells.items())
            if cell.text
        )

    def _value(self, node, shared, formats, epoch_1904):
        kind = node.get("t", "")
        value_node = _child(node, "v")
        value = value_node.text or "" if value_node is not None else ""
        if _child(node, "f") is not None and not value:
            return "(수식 결과 미저장: 원문 확인)"
        if kind == "inlineStr":
            inline = _child(node, "is")
            return _strings(inline) if inline is not None else ""
        if kind == "s":
            index = int(value)
            if index < 0:
                raise ValueError("negative shared string index")
            return shared[index]
        if kind == "b":
            return {"1": "TRUE", "0": "FALSE"}.get(value, value)
        if kind == "e":
            return f"(셀 오류: {value})"
        style = int(node.get("s", "0"))
        date_format = formats[style] if 0 <= style < len(formats) else 0
        if value and date_format in _DATE_FORMATS and kind not in ("str", "d"):
            try:
                days = float(Decimal(value))
                if not epoch_1904 and 60 <= days < 61:
                    return "(Excel 날짜 오류: 1900-02-29)"
                offset = days if epoch_1904 or days >= 60 else days + 1
                date = datetime(1904, 1, 1) if epoch_1904 else datetime(1899, 12, 30)
                result = date + timedelta(days=offset)
                return (
                    result.isoformat(sep=" ", timespec="seconds")
                    if days % 1
                    else result.date().isoformat()
                )
            except (InvalidOperation, ValueError, OverflowError):
                return value
        return value
