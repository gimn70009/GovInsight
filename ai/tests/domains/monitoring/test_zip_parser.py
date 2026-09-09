import tempfile
import zipfile
from pathlib import Path

import pytest

from app.domains.monitoring.parsers.base import AttachmentParseError, ParsedAttachment
from app.domains.monitoring.parsers.zip_parser import ZipParseError, ZipParser


class TextParser:
    def parse(self, file_path: Path) -> ParsedAttachment:
        return ParsedAttachment(text=file_path.read_text(encoding="utf-8"))


class FailingParser:
    def parse(self, _file_path: Path) -> ParsedAttachment:
        raise AttachmentParseError("문서를 읽을 수 없습니다.")


def _write_zip(path: Path, entries: dict[str, bytes | str]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)


def test_extracts_supported_documents_and_ignores_failed_or_unsupported_entries() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "documents.zip"
        _write_zip(
            path,
            {
                "folder/first.pdf": "첫 번째 문서",
                "broken.hwp": b"broken",
                "ignored.txt": "분석하지 않는 문서",
                "nested.zip": b"nested archive",
            },
        )
        parser = ZipParser(
            {".pdf": TextParser(), ".hwp": FailingParser()},
            max_entry_count=20,
            max_uncompressed_size_bytes=1024,
        )

        result = parser.parse(path)

    assert result.text == "[파일: first.pdf]\n첫 번째 문서"


def test_rejects_zip_without_supported_documents() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "unsupported.zip"
        _write_zip(path, {"notice.txt": "안내"})

        with pytest.raises(ZipParseError, match="분석할 수 있는 문서가 없습니다"):
            ZipParser({}, 20, 1024).parse(path)


def test_rejects_zip_when_entry_count_exceeds_limit() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "many-files.zip"
        _write_zip(path, {"one.pdf": "1", "two.pdf": "2"})

        with pytest.raises(ZipParseError, match="파일 수"):
            ZipParser({".pdf": TextParser()}, 1, 1024).parse(path)


def test_rejects_zip_when_uncompressed_size_exceeds_limit() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "large.zip"
        _write_zip(path, {"large.pdf": b"12345"})

        with pytest.raises(ZipParseError, match="압축 해제 크기"):
            ZipParser({".pdf": TextParser()}, 20, 4).parse(path)


def test_rejects_invalid_zip() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "broken.zip"
        path.write_bytes(b"not a zip file")

        with pytest.raises(ZipParseError, match="읽을 수 없습니다"):
            ZipParser({".pdf": TextParser()}, 20, 1024).parse(path)


class EncodedZipInfo(zipfile.ZipInfo):
    """Write real legacy name bytes into both ZIP headers without a UTF-8 flag."""

    def __init__(self, filename: str, encoding: str) -> None:
        super().__init__(filename)
        self.encoding = encoding

    def _encodeFilenameFlags(self) -> tuple[bytes, int]:
        return self.filename.encode(self.encoding), self.flag_bits & ~0x800


def test_recovers_korean_names_in_mixed_encoding_zip(tmp_path: Path) -> None:
    path = tmp_path / "mixed.zip"
    names = [
        "붙임 1. 신청서 양식.hwpx",
        "붙임 2. 신청서 영문요약 양식.hwpx",
        "붙임 3. 별첨 양식.hwpx",
    ]
    with zipfile.ZipFile(path, "w") as archive:
        for index, name in enumerate(names):
            archive.writestr(EncodedZipInfo(f"서식/{name}", "cp949"), f"문서 {index}")
        archive.writestr("정상/한글 양식.hwpx", "UTF-8 문서")
        archive.writestr("form.hwpx", "ASCII 문서")
    with zipfile.ZipFile(path) as archive:
        assert archive.infolist()[0].filename != f"서식/{names[0]}"
        assert archive.infolist()[0].flag_bits & 0x800 == 0
        assert archive.infolist()[3].flag_bits & 0x800

    result = ZipParser({".hwpx": TextParser()}, 20, 1024).parse(path)

    assert result.text == "\n\n".join([
        *(f"[파일: {name}]\n문서 {index}" for index, name in enumerate(names)),
        "[파일: 한글 양식.hwpx]\nUTF-8 문서",
        "[파일: form.hwpx]\nASCII 문서",
    ])


@pytest.mark.parametrize("name,encoding", [
    ("café.hwpx", "cp437"),
    ("éé.hwpx", "cp437"),
    ("╡.hwpx", "cp437"),  # Incomplete CP949 sequence.
    ("╩í.hwpx", "cp437"),  # Decodes to Hanja, not a Korean filename.
    ("Résumé.hwpx", "utf-8"),
    ("日本語.hwpx", "utf-8"),
    ("붙임 1. 신청서 양식.hwpx".encode("cp949").decode("cp437"), "utf-8"),
])
def test_preserves_other_names_and_explicit_utf8_metadata(
    tmp_path: Path, name: str, encoding: str,
) -> None:
    path = tmp_path / "unchanged.zip"
    with zipfile.ZipFile(path, "w") as archive:
        entry = name if encoding == "utf-8" else EncodedZipInfo(name, encoding)
        archive.writestr(entry, "원문")

    result = ZipParser({".hwpx": TextParser()}, 20, 1024).parse(path)

    assert result.text == f"[파일: {name}]\n원문"
