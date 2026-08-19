import re
from pathlib import Path

from pypdf import PdfReader
from pypdf.errors import PdfReadError

from app.domains.monitoring.parsers.base import AttachmentParseError, ParsedAttachment

MAX_PAGE_COUNT = 2_000


class PdfParseError(AttachmentParseError):
    pass


class PdfParser:
    def parse(self, file_path: Path) -> ParsedAttachment:
        try:
            reader = PdfReader(file_path, strict=False)
            if reader.is_encrypted:
                raise PdfParseError("암호화된 PDF 파일은 처리할 수 없습니다.")
            if len(reader.pages) > MAX_PAGE_COUNT:
                raise PdfParseError("PDF 페이지 수가 허용된 범위를 초과했습니다.")

            pages = [self._extract_page_text(page) for page in reader.pages]
        except PdfParseError:
            raise
        except (PdfReadError, OSError, TypeError, ValueError) as exception:
            raise PdfParseError("PDF 파일을 읽을 수 없습니다.") from exception

        text = "\n\n".join(page for page in pages if page).strip()
        if not text:
            raise PdfParseError("PDF 파일에서 텍스트를 찾지 못했습니다.")
        return ParsedAttachment(text=text)

    def _extract_page_text(self, page: object) -> str:
        extract_text = getattr(page, "extract_text", None)
        if not callable(extract_text):
            return ""
        return _normalize_page_text(extract_text() or "")


def _normalize_page_text(value: str) -> str:
    lines = [re.sub(r"[ \t\r\f\v]+", " ", line).strip() for line in value.splitlines()]
    return "\n".join(line for line in lines if line)
