import tempfile
from pathlib import Path

import pytest

from app.domains.monitoring.parsers import pdf_parser
from app.domains.monitoring.parsers.pdf_parser import PdfParseError, PdfParser


class FakePage:
    def __init__(self, text: str | None) -> None:
        self.text = text

    def extract_text(self) -> str | None:
        return self.text


class FakeReader:
    def __init__(self, pages: list[FakePage], encrypted: bool = False) -> None:
        self.pages = pages
        self.is_encrypted = encrypted


def test_extracts_multiple_pages_in_original_order(monkeypatch: pytest.MonkeyPatch) -> None:
    reader = FakeReader(
        [
            FakePage(" 첫 번째   페이지\n\n지원 사업 "),
            FakePage(None),
            FakePage("두 번째\t페이지"),
        ]
    )
    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_args, **_kwargs: reader)

    result = PdfParser().parse(Path("sample.pdf"))

    assert result.text == "첫 번째 페이지\n지원 사업\n\n두 번째 페이지"


def test_rejects_pdf_without_text(monkeypatch: pytest.MonkeyPatch) -> None:
    reader = FakeReader([FakePage(None), FakePage("  \n\t")])
    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_args, **_kwargs: reader)

    with pytest.raises(PdfParseError, match="텍스트를 찾지 못했습니다"):
        PdfParser().parse(Path("image-only.pdf"))


def test_rejects_encrypted_pdf(monkeypatch: pytest.MonkeyPatch) -> None:
    reader = FakeReader([], encrypted=True)
    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_args, **_kwargs: reader)

    with pytest.raises(PdfParseError, match="암호화된 PDF"):
        PdfParser().parse(Path("encrypted.pdf"))


def test_rejects_broken_pdf() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "broken.pdf"
        path.write_bytes(b"not a pdf file")

        with pytest.raises(PdfParseError, match="읽을 수 없습니다"):
            PdfParser().parse(path)
