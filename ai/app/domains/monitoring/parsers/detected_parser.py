import zipfile
from collections.abc import Mapping
from pathlib import Path

from app.domains.monitoring.parsers.base import AttachmentTextParser, ParsedAttachment
from app.domains.monitoring.parsers.hwpx_parser import SECTION_PATH

OLE_SIGNATURE = bytes.fromhex("d0cf11e0a1b11ae1")


def detect_document_extension(path: Path, declared: str) -> str:
    with path.open("rb") as file:
        header = file.read(8)
    if header.startswith(b"%PDF-"):
        return ".pdf"
    if header == OLE_SIGNATURE:
        return ".hwp"
    if header.startswith(b"PK"):
        try:
            with zipfile.ZipFile(path) as archive:
                if any(SECTION_PATH.fullmatch(e.filename.replace("\\", "/"))
                       for e in archive.infolist()):
                    return ".hwpx"
        except (OSError, zipfile.BadZipFile):
            pass
    return declared


class DetectedDocumentParser:
    def __init__(self, declared: str, parsers: Mapping[str, AttachmentTextParser]):
        self.declared = declared
        self.parsers = parsers

    def parse(self, file_path: Path) -> ParsedAttachment:
        extension = detect_document_extension(file_path, self.declared)
        return self.parsers[extension].parse(file_path)
