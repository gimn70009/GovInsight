import hashlib
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
from app.domains.monitoring.parsers.detected_parser import detect_document_extension

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
                if not entries:
                    raise ZipParseError("ZIP 내부에 문서가 없습니다.")
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
        manifest: list[dict] = []
        documents: list[tuple[int, str, str, str]] = []
        extracted_size = 0
        with tempfile.TemporaryDirectory(prefix="govinsight-zip-") as directory:
            for index, entry in enumerate(entries):
                name = _display_name(_entry_name(entry))
                declared = attachment_file_extension(entry.filename)
                item = {"fileName": name, "status": "UNSUPPORTED", "partIndex": None,
                        "reason": "미지원 형식", "actualFormat": None}
                manifest.append(item)
                if declared not in self.parsers:
                    continue
                target = Path(directory) / f"{index}{declared}"
                try:
                    extracted_size = self._copy_entry(archive, entry, target, extracted_size)
                    actual = detect_document_extension(target, declared)
                    item["actualFormat"] = actual.removeprefix(".").upper()
                    parsed = self.parsers.get(actual, self.parsers[declared]).parse(target)
                    if not parsed.text.strip():
                        raise AttachmentParseError("문서에서 본문을 찾지 못했습니다.")
                    documents.append((index, name, parsed.text.strip(), actual))
                    item.update(status="COMPLETED", reason="")
                except ZipParseError:
                    raise
                except (AttachmentParseError, OSError, RuntimeError, zipfile.BadZipFile):
                    item.update(status="FAILED", reason="읽기 실패")

        groups: dict[str, list[tuple[int, str, str, str]]] = {}
        for document in documents:
            # Preserve wording, numbers and line breaks; only normalize surrounding whitespace.
            normalized = "\n".join(line.strip() for line in document[2].splitlines()).strip()
            digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
            groups.setdefault(digest, []).append(document)
        representatives = []
        for group in groups.values():
            representative = min(group, key=lambda doc: (
                attachment_file_extension(doc[1]) != doc[3], doc[0]))
            representatives.append((representative, group))
        representatives.sort(key=lambda pair: pair[0][0])
        sections = []
        for part_index, (representative, group) in enumerate(representatives):
            sections.append(f"[파일: {representative[1]}]\n{representative[2]}")
            for document in group:
                manifest[document[0]]["partIndex"] = part_index
                if document[0] != representative[0]:
                    manifest[document[0]]["status"] = "DUPLICATE"
        return ParsedAttachment(text="\n\n".join(sections), archive_entries=tuple(manifest))

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


def _entry_name(entry: zipfile.ZipInfo) -> str:
    value = entry.filename
    # Respect explicit UTF-8 metadata. Legacy Korean ZIPs often have no encoding flag.
    if entry.flag_bits & 0x800 or not any("\u2500" <= char <= "\u259f" for char in value):
        return value
    try:
        raw_name = value.encode("cp437")
        decoded = raw_name.decode("cp949")
        if any("가" <= char <= "힣" for char in decoded) and decoded.encode("cp949") == raw_name:
            return decoded
    except UnicodeError:
        pass
    return value


def _display_name(value: str) -> str:
    name = value.replace("\\", "/").rsplit("/", maxsplit=1)[-1]
    return name.replace("\r", " ").replace("\n", " ")[:500] or "이름 없는 문서"
