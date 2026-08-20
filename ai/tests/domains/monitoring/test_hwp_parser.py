import struct
import zlib
from io import BytesIO
from pathlib import Path

import pytest

from app.domains.monitoring.parsers.hwp_parser import (
    COMPRESSED_FLAG,
    ENCRYPTED_FLAG,
    HWPTAG_PARA_TEXT,
    HwpParseError,
    HwpParser,
)


class FakeOleDocument:
    def __init__(self, properties: int, sections: dict[str, bytes]) -> None:
        self.streams = {
            "FileHeader": _file_header(properties),
            **{f"BodyText/{name}": data for name, data in sections.items()},
        }

    def __enter__(self) -> "FakeOleDocument":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def exists(self, name: str) -> bool:
        return name in self.streams

    def openstream(self, name: str | list[str]) -> BytesIO:
        key = "/".join(name) if isinstance(name, list) else name
        return BytesIO(self.streams[key])

    def listdir(self, streams: bool, storages: bool) -> list[list[str]]:
        assert streams is True
        assert storages is False
        return [name.split("/") for name in self.streams if "/" in name]


def test_extracts_text_from_uncompressed_sections_in_number_order(monkeypatch) -> None:
    document = FakeOleDocument(
        properties=0,
        sections={
            "Section10": _paragraph_record("세 번째"),
            "Section2": _paragraph_record("두 번째"),
            "Section0": _paragraph_record("첫 번째"),
        },
    )
    _use_fake_document(monkeypatch, document)

    result = HwpParser().parse(Path("sample.hwp"))

    assert result.text == "첫 번째\n두 번째\n세 번째"


def test_decompresses_body_text_and_removes_inline_control(monkeypatch) -> None:
    text = "지원사업".encode("utf-16le")
    inline_control = struct.pack("<8H", 3, 65, 66, 67, 68, 69, 70, 71)
    section = _record(HWPTAG_PARA_TEXT, text + inline_control + " 공고".encode("utf-16le"))
    compressor = zlib.compressobj(wbits=-15)
    compressed = compressor.compress(section) + compressor.flush()
    document = FakeOleDocument(COMPRESSED_FLAG, {"Section0": compressed})
    _use_fake_document(monkeypatch, document)

    result = HwpParser().parse(Path("compressed.hwp"))

    assert result.text == "지원사업 공고"


def test_rejects_non_ole_file(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.domains.monitoring.parsers.hwp_parser.olefile.isOleFile",
        lambda _path: False,
    )

    with pytest.raises(HwpParseError, match="OLE 파일이 아닙니다"):
        HwpParser().parse(Path("invalid.hwp"))


def test_rejects_encrypted_document(monkeypatch) -> None:
    document = FakeOleDocument(ENCRYPTED_FLAG, {"Section0": _paragraph_record("본문")})
    _use_fake_document(monkeypatch, document)

    with pytest.raises(HwpParseError, match="암호화된 HWP"):
        HwpParser().parse(Path("encrypted.hwp"))


def test_rejects_document_without_text(monkeypatch) -> None:
    document = FakeOleDocument(0, {"Section0": _record(1, b"metadata")})
    _use_fake_document(monkeypatch, document)

    with pytest.raises(HwpParseError, match="텍스트를 찾지 못했습니다"):
        HwpParser().parse(Path("empty.hwp"))


def _use_fake_document(monkeypatch, document: FakeOleDocument) -> None:
    monkeypatch.setattr(
        "app.domains.monitoring.parsers.hwp_parser.olefile.isOleFile",
        lambda _path: True,
    )
    monkeypatch.setattr(
        "app.domains.monitoring.parsers.hwp_parser.olefile.OleFileIO",
        lambda _path: document,
    )


def _file_header(properties: int) -> bytes:
    header = bytearray(256)
    header[:17] = b"HWP Document File"
    struct.pack_into("<I", header, 32, 0x05000302)
    struct.pack_into("<I", header, 36, properties)
    return bytes(header)


def _paragraph_record(text: str) -> bytes:
    return _record(HWPTAG_PARA_TEXT, text.encode("utf-16le"))


def _record(tag_id: int, data: bytes, level: int = 1) -> bytes:
    if len(data) < 0xFFF:
        header = tag_id | (level << 10) | (len(data) << 20)
        return struct.pack("<I", header) + data
    header = tag_id | (level << 10) | (0xFFF << 20)
    return struct.pack("<II", header, len(data)) + data
