import logging
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


class _VlmPdfParser:
    def __init__(self):
        self._parser = None
        self.document_key = "standalone"

    def start_document(self, document_key: str) -> None:
        # Keep lazy imports, but never carry a notice's counters into the next notice.
        self._parser = None
        self.document_key = document_key

    def get_parser(self):
        if self._parser is None:
            # One notice shares its OCR budget across PDF, HWP, HWPX and ZIP entries.
            try:
                module = import_module("app.domains.monitoring.parsers.pdf_parser")
                from app.domains.monitoring.parsers.vlm_config import VlmOcrSettings
                from app.domains.monitoring.parsers.vlm_ocr import VlmOcr

                self._parser = module.PdfParser(ocr=VlmOcr(
                    VlmOcrSettings.from_env(), document_key=self.document_key,
                ))
            except (ImportError, OSError, ValueError):
                logging.getLogger(__name__).warning("vlm_ocr unavailable; using local PDF parser")
                self._parser = _LazyParser("pdf_parser", "PdfParser")
        return self._parser

    def parse(self, file_path: Path) -> ParsedAttachment:
        return self.get_parser().parse(file_path)


class _VlmHwpParser:
    def __init__(self, local_parser, pdf_parser):
        self.local_parser = local_parser
        self.pdf_parser = pdf_parser

    def parse(self, file_path: Path) -> ParsedAttachment:
        try:
            ocr = getattr(self.pdf_parser.get_parser(), "ocr", None)
            if ocr is None or not ocr.settings.enabled:
                return self.local_parser.parse(file_path)
            from app.domains.monitoring.parsers.hwp_vlm_parser import HwpVlmParser

            return HwpVlmParser(self.local_parser, ocr).parse(file_path)
        except (ImportError, OSError):
            return self.local_parser.parse(file_path)


class AttachmentParserRegistry:
    def __init__(self) -> None:
        pdf_parser = self._pdf_parser = _VlmPdfParser()
        document_parsers: dict[str, AttachmentTextParser] = {
            ".hwp": _VlmHwpParser(_LazyParser("hwp_parser", "HwpParser"), pdf_parser),
            ".hwpx": _VlmHwpParser(_LazyParser("hwpx_parser", "HwpxParser"), pdf_parser),
            ".pdf": pdf_parser,
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

    def start_document(self, document_key: str) -> None:
        self._pdf_parser.start_document(document_key)

    def find(self, file_name: str) -> AttachmentTextParser | None:
        return self._parsers.get(attachment_file_extension(file_name))
