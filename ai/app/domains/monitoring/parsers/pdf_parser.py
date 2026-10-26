import re
from collections import Counter
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
        plain = _normalize_page_text(extract_text() or "")
        lines = plain.splitlines()
        # Layout is more expensive on long reference PDFs. Use it only when text
        # runs have collapsed into a dominant line or merged contact-table cells.
        collapsed = bool(lines and max(map(len, lines)) > max(120, len(plain) * 0.7))
        # A small contact table can be flattened even when the surrounding prose
        # keeps the longest line below the generic collapse threshold.
        merged_contacts = any(
            re.search(r"전화|이메일|문의처", line)
            and (len(re.findall(r"0\d{1,2}[- ]\d{3,4}[- ]\d{4}", line)) >= 2
                 or line.count("@") >= 2)
            for line in lines
        )
        submission_table = bool(
            re.search(r"제출\s*서류|서류명", plain)
            and len(re.findall(
                r"주요\s*내용|비고|필수|해당\s*시|파일\s*형식|제출\s*대상", plain,
            )) >= 2
        )
        if collapsed or merged_contacts or submission_table:
            try:
                raw_layout = extract_text(extraction_mode="layout") or ""
                layout = _normalize_layout_text(raw_layout)
                # Layout can omit rotated text. Do not trade original conditions
                # or numbers for a prettier but incomplete page.
                unchanged = (
                    Counter(re.sub(r"\s+", "", layout)) == Counter(re.sub(r"\s+", "", plain))
                )
                if layout and unchanged:
                    return layout
            except (TypeError, ValueError, NotImplementedError):
                pass
        return plain



def _normalize_page_text(value: str) -> str:
    lines = [re.sub(r"[ \t\r\f\v]+", " ", line).strip() for line in value.splitlines()]
    return "\n".join(line for line in lines if line)



def _normalize_layout_text(value: str) -> str:
    # pypdf's existing layout pass supplies alignment spaces. Preserve them as
    # column separators; these are layout hints, not certified table cells.
    return "\n".join(
        re.sub(r"[ \t]{2,}", "\t", line.strip())
        for line in value.splitlines() if line.strip()
    )
