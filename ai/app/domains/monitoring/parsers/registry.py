from pathlib import Path

from app.domains.monitoring.parsers.base import AttachmentTextParser
from app.domains.monitoring.parsers.hwpx_parser import HwpxParser


class AttachmentParserRegistry:
    def __init__(self) -> None:
        self._parsers: dict[str, AttachmentTextParser] = {".hwpx": HwpxParser()}

    def find(self, file_name: str) -> AttachmentTextParser | None:
        return self._parsers.get(Path(file_name).suffix.lower())
