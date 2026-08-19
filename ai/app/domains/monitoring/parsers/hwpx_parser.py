import re
import zipfile
from pathlib import Path
from xml.etree import ElementTree

from app.domains.monitoring.parsers.base import AttachmentParseError, ParsedAttachment

SECTION_PATH = re.compile(r"^Contents/section(\d+)\.xml$", re.IGNORECASE)
MAX_SECTION_COUNT = 1_000
MAX_XML_SIZE_BYTES = 50 * 1024 * 1024


class HwpxParseError(AttachmentParseError):
    pass


class HwpxParser:
    def parse(self, file_path: Path) -> ParsedAttachment:
        try:
            with zipfile.ZipFile(file_path) as archive:
                paragraphs: list[str] = []
                for entry in self._section_entries(archive):
                    if entry.file_size > MAX_XML_SIZE_BYTES:
                        raise HwpxParseError("HWPX 본문 XML의 크기가 너무 큽니다.")
                    paragraphs.extend(self._parse_section(archive.read(entry)))
        except (zipfile.BadZipFile, OSError, ElementTree.ParseError) as exception:
            raise HwpxParseError("HWPX 파일을 읽을 수 없습니다.") from exception

        text = "\n".join(paragraphs).strip()
        if not text:
            raise HwpxParseError("HWPX 파일에서 텍스트를 찾지 못했습니다.")
        return ParsedAttachment(text=text)

    def _section_entries(self, archive: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
        entries: list[tuple[int, zipfile.ZipInfo]] = []
        for entry in archive.infolist():
            match = SECTION_PATH.fullmatch(entry.filename.replace("\\", "/"))
            if match:
                entries.append((int(match.group(1)), entry))

        if not entries:
            raise HwpxParseError("HWPX 본문 XML을 찾지 못했습니다.")
        if len(entries) > MAX_SECTION_COUNT:
            raise HwpxParseError("HWPX 본문 섹션 수가 너무 많습니다.")
        return [entry for _number, entry in sorted(entries, key=lambda item: item[0])]

    def _parse_section(self, xml_bytes: bytes) -> list[str]:
        root = ElementTree.fromstring(xml_bytes)
        paragraphs: list[str] = []
        for paragraph in root.iter():
            if _local_name(paragraph.tag) != "p":
                continue
            text = _normalize_text(
                "".join(
                    node.text or ""
                    for node in paragraph.iter()
                    if _local_name(node.tag) == "t"
                )
            )
            if text:
                paragraphs.append(text)
        return paragraphs


def _local_name(tag: str) -> str:
    return tag.rsplit("}", maxsplit=1)[-1]


def _normalize_text(value: str) -> str:
    return re.sub(r"[ \t\r\f\v]+", " ", value).strip()
