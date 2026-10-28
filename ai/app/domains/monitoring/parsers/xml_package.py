"""Read XML packages without office automation or additional native modules."""

import re
import zipfile
from xml.etree import ElementTree

from app.domains.monitoring.parsers.base import AttachmentParseError

MAX_PACKAGE_BYTES = 100 * 1024 * 1024
MAX_PART_BYTES = 50 * 1024 * 1024
MAX_PACKAGE_ENTRIES = 10_000


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def attribute(node: ElementTree.Element, name: str, default: str = "") -> str:
    return next((value for key, value in node.attrib.items() if local_name(key) == name), default)


def parse_xml(data: bytes) -> ElementTree.Element:
    if re.search(rb"<!\s*(?:DOCTYPE|ENTITY)\b", data.replace(b"\x00", b""), re.I):
        raise AttachmentParseError("DTD/ENTITY가 포함된 문서는 처리할 수 없습니다.")
    return ElementTree.fromstring(data)


def validate_package(archive: zipfile.ZipFile) -> None:
    entries = archive.infolist()
    if len(entries) > MAX_PACKAGE_ENTRIES or sum(e.file_size for e in entries) > MAX_PACKAGE_BYTES:
        raise AttachmentParseError("문서 압축 해제 크기 또는 파일 수가 너무 큽니다.")
    if len({e.filename for e in entries}) != len(entries):
        raise AttachmentParseError("문서 내부에 중복된 파일 경로가 있습니다.")
    if any(e.flag_bits & 1 for e in entries):
        raise AttachmentParseError("암호화된 문서는 처리할 수 없습니다.")


def read_xml(archive: zipfile.ZipFile, name: str) -> ElementTree.Element:
    entry = archive.getinfo(name)
    if entry.file_size > MAX_PART_BYTES:
        raise AttachmentParseError("문서 XML의 크기가 너무 큽니다.")
    if entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
        raise AttachmentParseError("지원하지 않는 문서 압축 방식입니다.")
    return parse_xml(archive.read(entry))


def text_blocks(root: ElementTree.Element, table_reader) -> list[str]:
    blocks: list[str] = []
    fragments: list[str] = []

    def flush():
        text = re.sub(r"[ \t\r\f\v]+", " ", "".join(fragments)).strip()
        if text:
            blocks.append(text)
        fragments.clear()

    def visit(node, depth=0):
        if depth > 128:
            raise AttachmentParseError("문서 XML의 중첩 깊이가 너무 큽니다.")
        tag = local_name(node.tag)
        if tag in ("del", "moveFrom"):
            return  # Word tracked deletions are not current instructions.
        if tag == "tbl":
            flush()
            table = table_reader(node)
            if table:
                blocks.append(table)
                return
        if tag == "p":
            flush()
        if tag == "t":
            fragments.append("".join(node.itertext()))
        elif tag in ("br", "lineBreak"):
            flush()
        elif tag == "tab":
            fragments.append(" ")
        else:
            for child in node:
                visit(child, depth + 1)
        if tag == "p":
            flush()

    visit(root)
    flush()
    return blocks
