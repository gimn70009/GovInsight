from app.domains.monitoring.file_names import attachment_file_extension
from app.domains.monitoring.parsers.base import AttachmentTextParser
from app.domains.monitoring.parsers.hwp_parser import HwpParser
from app.domains.monitoring.parsers.hwpx_parser import HwpxParser
from app.domains.monitoring.parsers.pdf_parser import PdfParser


class AttachmentParserRegistry:
    def __init__(self) -> None:
        self._parsers: dict[str, AttachmentTextParser] = {
            ".hwp": HwpParser(),
            ".hwpx": HwpxParser(),
            ".pdf": PdfParser(),
        }

    def find(self, file_name: str) -> AttachmentTextParser | None:
        return self._parsers.get(attachment_file_extension(file_name))
