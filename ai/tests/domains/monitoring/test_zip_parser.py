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


def test_lists_zip_without_supported_documents() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "unsupported.zip"
        _write_zip(path, {"notice.txt": "안내"})

        result = ZipParser({}, 20, 1024).parse(path)
        assert result.text == ""
        assert result.archive_entries[0]["fileName"] == "notice.txt"
        assert result.archive_entries[0]["status"] == "UNSUPPORTED"


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

    assert result.text == "\n\n".join(
        [
            *(f"[파일: {name}]\n문서 {index}" for index, name in enumerate(names)),
            "[파일: 한글 양식.hwpx]\nUTF-8 문서",
            "[파일: form.hwpx]\nASCII 문서",
        ]
    )


@pytest.mark.parametrize(
    "name,encoding",
    [
        ("café.hwpx", "cp437"),
        ("éé.hwpx", "cp437"),
        ("╡.hwpx", "cp437"),  # Incomplete CP949 sequence.
        ("╩í.hwpx", "cp437"),  # Decodes to Hanja, not a Korean filename.
        ("Résumé.hwpx", "utf-8"),
        ("日本語.hwpx", "utf-8"),
        ("붙임 1. 신청서 양식.hwpx".encode("cp949").decode("cp437"), "utf-8"),
    ],
)
def test_preserves_other_names_and_explicit_utf8_metadata(
    tmp_path: Path,
    name: str,
    encoding: str,
) -> None:
    path = tmp_path / "unchanged.zip"
    with zipfile.ZipFile(path, "w") as archive:
        entry = name if encoding == "utf-8" else EncodedZipInfo(name, encoding)
        archive.writestr(entry, "원문")

    result = ZipParser({".hwpx": TextParser()}, 20, 1024).parse(path)

    assert result.text == f"[파일: {name}]\n원문"


def test_records_every_file_without_putting_failures_in_text(tmp_path: Path) -> None:
    path = tmp_path / "files.zip"
    _write_zip(path, {"ok.pdf": "본문", "broken.hwp": "오류", "file.docx": "미지원"})
    result = ZipParser({".pdf": TextParser(), ".hwp": FailingParser()}, 20, 1024).parse(path)
    assert [item["status"] for item in result.archive_entries] == [
        "COMPLETED",
        "FAILED",
        "UNSUPPORTED",
    ]
    assert result.text == "[파일: ok.pdf]\n본문"


def test_same_name_with_different_text_is_not_merged(tmp_path: Path) -> None:
    path = tmp_path / "files.zip"
    _write_zip(path, {"one/form.pdf": "지원금 100원", "two/form.pdf": "지원금 200원"})
    result = ZipParser({".pdf": TextParser()}, 20, 1024).parse(path)
    assert [item["partIndex"] for item in result.archive_entries] == [0, 1]
    assert all(item["status"] == "COMPLETED" for item in result.archive_entries)


def test_identical_extracted_text_is_grouped_conservatively(tmp_path: Path) -> None:
    path = tmp_path / "files.zip"
    _write_zip(path, {"a.pdf": "목표\n  방법  ", "b.hwp": "목표\n방법", "c.pdf": "목표 방법"})
    result = ZipParser({".pdf": TextParser(), ".hwp": TextParser()}, 20, 1024).parse(path)
    assert [item["status"] for item in result.archive_entries] == [
        "COMPLETED",
        "DUPLICATE",
        "COMPLETED",
    ]
    assert [item["partIndex"] for item in result.archive_entries] == [0, 0, 1]
    assert result.text.count("[파일:") == 2


def test_all_failed_zip_retains_diagnostics(tmp_path: Path) -> None:
    path = tmp_path / "files.zip"
    _write_zip(path, {"broken.hwp": "실패"})
    result = ZipParser({".hwp": FailingParser()}, 20, 1024).parse(path)
    assert not result.text
    assert result.archive_entries[0]["status"] == "FAILED"
