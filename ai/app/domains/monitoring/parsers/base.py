from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class ParsedAttachment:
    text: str


class AttachmentTextParser(Protocol):
    def parse(self, file_path: Path) -> ParsedAttachment: ...
