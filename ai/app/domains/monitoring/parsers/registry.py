from importlib import import_module
from pathlib import Path

from app.core.config import ZIP_MAX_ENTRY_COUNT, ZIP_MAX_UNCOMPRESSED_SIZE_BYTES
from app.domains.monitoring.file_names import attachment_file_extension
from app.domains.monitoring.parsers.base import (
    AttachmentParseError,
    AttachmentTextParser,
    ParsedAttachment,
)
from app.domains.monitoring.parsers.detected_parser import DetectedDocumentParser
from app.domains.monitoring.parsers.zip_parser import ZipParser


class _LazyParser:
    def __init__(self, module: str, class_name: str):
        self.module = module
        self.class_name = class_name

    def parse(self, file_path: Path) -> ParsedAttachment:
        # A policy-blocked optional file reader must not prevent app startup or
        # parsing of unrelated attachments. No package installation is attempted.
        try:
            module = import_module(f"app.domains.monitoring.parsers.{self.module}")
            parser = getattr(module, self.class_name)()
        except (ImportError, OSError) as exc:
            message = "문서 파서를 불러올 수 없습니다. 실행 환경을 확인해 주세요."
            raise AttachmentParseError(message) from exc
        return parser.parse(file_path)


class AttachmentParserRegistry:
    def __init__(self) -> None:
        document_parsers: dict[str, AttachmentTextParser] = {
            ".hwp": _LazyParser("hwp_parser", "HwpParser"),
            ".hwpx": _LazyParser("hwpx_parser", "HwpxParser"),
            ".pdf": _LazyParser("pdf_parser", "PdfParser"),
            ".docx": _LazyParser("docx_parser", "DocxParser"),
            ".xlsx": _LazyParser("xlsx_parser", "XlsxParser"),
        }
        self._parsers = {
            **{extension: DetectedDocumentParser(extension, document_parsers)
               for extension in document_parsers},
            ".zip": ZipParser(
                document_parsers,
                max_entry_count=ZIP_MAX_ENTRY_COUNT,
                max_uncompressed_size_bytes=ZIP_MAX_UNCOMPRESSED_SIZE_BYTES,
            ),
        }

    def find(self, file_name: str) -> AttachmentTextParser | None:
        return self._parsers.get(attachment_file_extension(file_name))
