import tempfile
import zipfile
from collections.abc import Mapping
from pathlib import Path

from app.domains.monitoring.file_names import attachment_file_extension
from app.domains.monitoring.parsers.base import (
    AttachmentParseError,
    AttachmentTextParser,
    ParsedAttachment,
)

COPY_CHUNK_SIZE = 64 * 1024


class ZipParseError(AttachmentParseError):
    pass


class ZipParser:
    def __init__(
        self,
        parsers: Mapping[str, AttachmentTextParser],
        max_entry_count: int,
        max_uncompressed_size_bytes: int,
    ) -> None:
        self.parsers = parsers
        self.max_entry_count = max_entry_count
        self.max_uncompressed_size_bytes = max_uncompressed_size_bytes

    def parse(self, file_path: Path) -> ParsedAttachment:
        try:
            with zipfile.ZipFile(file_path) as archive:
                entries = [entry for entry in archive.infolist() if not entry.is_dir()]
                self._validate_archive(entries)
                return self._parse_entries(archive, entries)
        except ZipParseError:
            raise
        except (OSError, RuntimeError, zipfile.BadZipFile, zipfile.LargeZipFile) as exception:
            raise ZipParseError("ZIP 파일을 읽을 수 없습니다.") from exception

    def _validate_archive(self, entries: list[zipfile.ZipInfo]) -> None:
        if len(entries) > self.max_entry_count:
            raise ZipParseError("ZIP 내부 파일 수가 허용된 범위를 초과했습니다.")
        if sum(entry.file_size for entry in entries) > self.max_uncompressed_size_bytes:
            raise ZipParseError("ZIP 압축 해제 크기가 허용된 범위를 초과했습니다.")

    def _parse_entries(
        self,
        archive: zipfile.ZipFile,
        entries: list[zipfile.ZipInfo],
    ) -> ParsedAttachment:
        supported_entries = [
            (entry, self.parsers.get(attachment_file_extension(entry.filename)))
            for entry in entries
            if self.parsers.get(attachment_file_extension(entry.filename)) is not None
        ]
        if not supported_entries:
            raise ZipParseError("ZIP 파일에 분석할 수 있는 문서가 없습니다.")

        parsed_sections: list[str] = []
        extracted_size = 0
        with tempfile.TemporaryDirectory(prefix="govinsight-zip-") as directory:
            temporary_directory = Path(directory)
            for index, (entry, parser) in enumerate(supported_entries):
                extension = attachment_file_extension(entry.filename)
                target = temporary_directory / f"{index}{extension}"
                try:
                    extracted_size = self._copy_entry(
                        archive,
                        entry,
                        target,
                        extracted_size,
                    )
                    parsed = parser.parse(target) if parser is not None else None
                except ZipParseError:
                    raise
                except (AttachmentParseError, OSError, RuntimeError, zipfile.BadZipFile):
                    continue
                if parsed is not None:
                    display_name = _display_name(entry.filename)
                    parsed_sections.append(f"[파일: {display_name}]\n{parsed.text}")

        if not parsed_sections:
            raise ZipParseError("ZIP 내부 문서를 읽을 수 없습니다.")
        return ParsedAttachment(text="\n\n".join(parsed_sections))

    def _copy_entry(
        self,
        archive: zipfile.ZipFile,
        entry: zipfile.ZipInfo,
        target: Path,
        extracted_size: int,
    ) -> int:
        with archive.open(entry) as source, target.open("wb") as output:
            while chunk := source.read(COPY_CHUNK_SIZE):
                extracted_size += len(chunk)
                if extracted_size > self.max_uncompressed_size_bytes:
                    raise ZipParseError("ZIP 압축 해제 크기가 허용된 범위를 초과했습니다.")
                output.write(chunk)
        return extracted_size


def _display_name(value: str) -> str:
    return value.replace("\\", "/").rsplit("/", maxsplit=1)[-1] or "이름 없는 문서"
