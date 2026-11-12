"""Add visual page readings without making imperfect text extraction an OCR gate."""

import hashlib
import io
import logging
from dataclasses import replace
from pathlib import Path

from pypdf import PdfReader

from app.domains.monitoring.parsers.base import ParsedAttachment
from app.domains.monitoring.parsers.hwp_pdf_converter import HwpPdfConverter

logger = logging.getLogger(__name__)


class HwpVlmParser:
    def __init__(self, local_parser, ocr, converter=None):
        self.local_parser = local_parser
        self.ocr = ocr
        self.converter = converter or HwpPdfConverter()

    def parse(self, path: Path) -> ParsedAttachment:
        original = self.local_parser.parse(path)
        if self.ocr is None or not self.ocr.settings.enabled:
            return original
        if not self.ocr.settings.api_key:
            return replace(original, warning="OCR API 키가 없어 기존 한글 추출문을 유지합니다.")
        if self.ocr.files >= self.ocr.settings.max_files_per_document:
            logger.info("hwp_vlm skipped document_key=%s reason=document_file_limit",
                        self.ocr.document_key)
            return replace(original, warning="게시글 OCR 파일 수 한도로 기존 추출문을 유지합니다.")
        if self.ocr.calls >= self.ocr.settings.max_calls_per_document:
            return replace(original, warning="게시글 OCR 호출 한도로 기존 추출문을 유지합니다.")
        stage = "conversion"
        try:
            converted = self.converter.convert(path)
            stage = "ocr"
            reader = PdfReader(io.BytesIO(converted.pdf))
            pages, warning = self.ocr.enhance(reader, converted.page_texts)
            readings = [
                f"[PDF {index + 1}페이지 시각 판독]\n{text}"
                for index, text in enumerate(pages)
                if text.strip() and text != converted.page_texts[index]
            ]
            if not readings:
                return replace(original, warning=warning)
            # HWP's local parser has no reliable page boundaries. Keep its full text
            # instead of replacing unselected pages with imperfect PDF extraction.
            text = "\n\n".join([*readings, "[문서 전체 텍스트 추출]\n" + original.text])
            return replace(original, text=text, warning=warning)
        except Exception as exc:
            # The optional renderer/Chromium must not take down collection.
            # No exception text, document contents or paths are logged.
            logger.warning(
                "hwp_vlm fallback document_key=%s file_key=%s stage=%s reason=%s code=%s",
                self.ocr.document_key, _file_key(path), stage, type(exc).__name__,
                _failure_code(exc),
            )
            return replace(
                original, warning="한글 PDF 변환/OCR 검증에 실패해 기존 추출문을 유지합니다.",
            )



# Match only messages created by our own checks. Never log arbitrary exception
# bodies from document libraries, paths or providers.
_FAILURE_CODES = {
    "HWP source too large": "input_size_limit",
    "HWP renderer asset checksum mismatch": "renderer_checksum",
    "Korean font unavailable": "font_unavailable",
    "HWP conversion capacity reached": "conversion_busy",
    "Invalid rendered page count": "rendered_page_limit",
    "Rendered document too large": "rendered_size_limit",
    "Rendered page batch too large": "rendered_page_size_limit",
    "Converted PDF too large": "pdf_size_limit",
    "PDF pagination changed": "pdf_page_count_mismatch",
    "Unsafe or oversized rendered SVG": "svg_size_or_declaration",
    "Invalid SVG": "invalid_svg",
    "Active SVG content": "active_svg_content",
    "Active SVG attribute": "active_svg_attribute",
    "External SVG reference": "external_svg_reference",
    "External SVG style": "external_svg_style",
    "Invalid SVG page size": "invalid_svg_dimensions",
    "SVG page size out of range": "svg_dimension_limit",
    "Invalid embedded SVG": "invalid_embedded_svg",
    "Foreign SVG namespace": "foreign_svg_namespace",
    "Embedded SVG nesting limit": "embedded_svg_nesting_limit",
}


def _failure_code(error: Exception) -> str:
    if isinstance(error, TimeoutError):
        return "conversion_timeout"
    if isinstance(error, OSError):
        return "runtime_or_file_access"
    return _FAILURE_CODES.get(str(error), "unclassified_error")


def _file_key(path: Path) -> str:
    try:
        with path.open("rb") as source:
            return hashlib.file_digest(source, "sha256").hexdigest()[:12]
    except OSError:
        return "unavailable"
