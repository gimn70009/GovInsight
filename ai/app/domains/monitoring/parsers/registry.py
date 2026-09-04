from app.core.config import ZIP_MAX_ENTRY_COUNT, ZIP_MAX_UNCOMPRESSED_SIZE_BYTES
from app.domains.monitoring.file_names import attachment_file_extension
from app.domains.monitoring.parsers.base import AttachmentTextParser
from app.domains.monitoring.parsers.hwp_parser import HwpParser
from app.domains.monitoring.parsers.hwpx_parser import HwpxParser
from app.domains.monitoring.parsers.pdf_parser import PdfParser
from app.domains.monitoring.parsers.zip_parser import ZipParser


class AttachmentParserRegistry:
    def __init__(self) -> None:
        document_parsers: dict[str, AttachmentTextParser] = {
            ".hwp": HwpParser(),
            ".hwpx": HwpxParser(),
            ".pdf": PdfParser(),
        }
        self._parsers = {
            **document_parsers,
            ".zip": ZipParser(
                document_parsers,
                max_entry_count=ZIP_MAX_ENTRY_COUNT,
                max_uncompressed_size_bytes=ZIP_MAX_UNCOMPRESSED_SIZE_BYTES,
            ),
        }

    def find(self, file_name: str) -> AttachmentTextParser | None:
        return self._parsers.get(attachment_file_extension(file_name))
