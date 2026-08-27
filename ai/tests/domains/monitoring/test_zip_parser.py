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
