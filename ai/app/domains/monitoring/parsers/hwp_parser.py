import re
import struct
import zlib
from pathlib import Path

import olefile

from app.domains.monitoring.parsers.base import AttachmentParseError, ParsedAttachment

FILE_HEADER_STREAM = "FileHeader"
BODY_TEXT_STORAGE = "BodyText"
HWP_SIGNATURE = b"HWP Document File"
FILE_PROPERTIES_OFFSET = 36
COMPRESSED_FLAG = 1 << 0
ENCRYPTED_FLAG = 1 << 1
DISTRIBUTION_FLAG = 1 << 2
DRM_FLAG = 1 << 4

HWPTAG_BEGIN = 0x010
HWPTAG_PARA_TEXT = HWPTAG_BEGIN + 51
EXTENDED_RECORD_SIZE = 0xFFF
SECTION_NAME = re.compile(r"^Section(\d+)$", re.IGNORECASE)

MAX_SECTION_COUNT = 1_000
MAX_DECOMPRESSED_SECTION_SIZE = 50 * 1024 * 1024

# HWP 문단 안에서 글자가 아닌 개체를 가리키는 컨트롤은 8개의 WCHAR를 차지한다.
CHAR_CONTROL_CODES = {0, 10, 13, 24, 25, 30, 31}


class HwpParseError(AttachmentParseError):
    pass


class HwpParser:
    def parse(self, file_path: Path) -> ParsedAttachment:
        if not olefile.isOleFile(file_path):
            raise HwpParseError("정상적인 HWP 5.0 OLE 파일이 아닙니다.")

        try:
            with olefile.OleFileIO(file_path) as document:
                properties = self._read_file_properties(document)
                self._validate_supported_document(properties)

                paragraphs: list[str] = []
                for stream_path in self._section_streams(document):
                    section = document.openstream(stream_path).read()
                    if properties & COMPRESSED_FLAG:
                        section = _decompress_section(section)
                    paragraphs.extend(_extract_paragraphs(section))
        except HwpParseError:
            raise
        except (OSError, ValueError, struct.error, olefile.OleFileError) as exception:
            raise HwpParseError("HWP 파일을 읽을 수 없습니다.") from exception

        text = "\n".join(paragraphs).strip()
        if not text:
            raise HwpParseError("HWP 파일에서 텍스트를 찾지 못했습니다.")
        return ParsedAttachment(text=text)

    def _read_file_properties(self, document: olefile.OleFileIO) -> int:
        if not document.exists(FILE_HEADER_STREAM):
            raise HwpParseError("HWP FileHeader 스트림을 찾지 못했습니다.")

        header = document.openstream(FILE_HEADER_STREAM).read()
        if len(header) < 40 or not header.startswith(HWP_SIGNATURE):
            raise HwpParseError("HWP FileHeader가 올바르지 않습니다.")
        return struct.unpack_from("<I", header, FILE_PROPERTIES_OFFSET)[0]

    def _validate_supported_document(self, properties: int) -> None:
        if properties & ENCRYPTED_FLAG:
            raise HwpParseError("암호화된 HWP 파일은 처리할 수 없습니다.")
        if properties & DISTRIBUTION_FLAG:
            raise HwpParseError("배포용 HWP 파일은 처리할 수 없습니다.")
        if properties & DRM_FLAG:
            raise HwpParseError("DRM이 적용된 HWP 파일은 처리할 수 없습니다.")

    def _section_streams(self, document: olefile.OleFileIO) -> list[list[str]]:
        sections: list[tuple[int, list[str]]] = []
        for stream_path in document.listdir(streams=True, storages=False):
            if len(stream_path) != 2 or stream_path[0].lower() != BODY_TEXT_STORAGE.lower():
                continue
            match = SECTION_NAME.fullmatch(stream_path[1])
            if match:
                sections.append((int(match.group(1)), stream_path))

        if not sections:
            raise HwpParseError("HWP 본문 Section 스트림을 찾지 못했습니다.")
        if len(sections) > MAX_SECTION_COUNT:
            raise HwpParseError("HWP 본문 섹션 수가 너무 많습니다.")
        return [path for _number, path in sorted(sections, key=lambda item: item[0])]


def _decompress_section(data: bytes) -> bytes:
    try:
        decompressor = zlib.decompressobj(wbits=-15)
        section = decompressor.decompress(data, MAX_DECOMPRESSED_SECTION_SIZE + 1)
        if len(section) > MAX_DECOMPRESSED_SECTION_SIZE or decompressor.unconsumed_tail:
            raise HwpParseError("압축 해제된 HWP 본문 크기가 너무 큽니다.")
        section += decompressor.flush()
    except zlib.error as exception:
        raise HwpParseError("HWP 본문 압축을 해제할 수 없습니다.") from exception

    if len(section) > MAX_DECOMPRESSED_SECTION_SIZE:
        raise HwpParseError("압축 해제된 HWP 본문 크기가 너무 큽니다.")
    return section


def _extract_paragraphs(section: bytes) -> list[str]:
    paragraphs: list[str] = []
    offset = 0

    while offset < len(section):
        if len(section) - offset < 4:
            raise HwpParseError("HWP 본문 레코드가 손상되었습니다.")

        header = struct.unpack_from("<I", section, offset)[0]
        offset += 4
        tag_id = header & 0x3FF
        size = (header >> 20) & 0xFFF

        if size == EXTENDED_RECORD_SIZE:
            if len(section) - offset < 4:
                raise HwpParseError("HWP 확장 레코드가 손상되었습니다.")
            size = struct.unpack_from("<I", section, offset)[0]
            offset += 4

        record_end = offset + size
        if record_end > len(section):
            raise HwpParseError("HWP 본문 레코드 크기가 올바르지 않습니다.")

        if tag_id == HWPTAG_PARA_TEXT:
            paragraph = _decode_paragraph_text(section[offset:record_end])
            if paragraph:
                paragraphs.append(paragraph)
        offset = record_end

    return paragraphs


def _decode_paragraph_text(data: bytes) -> str:
    if len(data) % 2 != 0:
        raise HwpParseError("HWP 문단 텍스트 길이가 올바르지 않습니다.")

    characters: list[str] = []
    position = 0
    unit_count = len(data) // 2
    while position < unit_count:
        code = struct.unpack_from("<H", data, position * 2)[0]
        if code < 32:
            position += 1 if code in CHAR_CONTROL_CODES else min(8, unit_count - position)
            continue
        if 0xD800 <= code <= 0xDBFF:
            if position + 1 < unit_count:
                low_surrogate = struct.unpack_from("<H", data, (position + 1) * 2)[0]
                if 0xDC00 <= low_surrogate <= 0xDFFF:
                    code_point = 0x10000 + ((code - 0xD800) << 10) + (low_surrogate - 0xDC00)
                    characters.append(chr(code_point))
                    position += 2
                    continue
            characters.append("�")
            position += 1
            continue
        if 0xDC00 <= code <= 0xDFFF:
            characters.append("�")
            position += 1
            continue
        characters.append(chr(code))
        position += 1

    return _normalize_text("".join(characters))


def _normalize_text(value: str) -> str:
    lines = [re.sub(r"[ \t\r\f\v]+", " ", line).strip() for line in value.splitlines()]
    return "\n".join(line for line in lines if line)
