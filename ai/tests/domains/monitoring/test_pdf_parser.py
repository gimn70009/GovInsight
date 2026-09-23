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


def test_layout_extraction_preserves_physical_table_rows(monkeypatch):
    class TablePage:
        def extract_text(self, **kwargs):
            if kwargs.get("extraction_mode") == "layout":
                return "주관기관   공동기관\n중소중견   제한없음\n문의처\n사업팀 02-0000-0000"
            return "주관기관공동기관중소중견제한없음문의처사업팀02-0000-0000" * 10

    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_a, **_kw: FakeReader([TablePage()]))
    result = PdfParser().parse(Path("table.pdf"))
    assert "주관기관 공동기관\n중소중견 제한없음" in result.text
    assert "문의처\n사업팀" in result.text


def test_empty_layout_falls_back_to_plain_extraction(monkeypatch):
    class Page:
        def extract_text(self, **kwargs):
            return "" if kwargs else "기존 추출 내용" * 30

    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_a, **_kw: FakeReader([Page()]))
    assert PdfParser().parse(Path("fallback.pdf")).text == "기존 추출 내용" * 30


def test_regular_pdf_does_not_pay_for_layout_pass(monkeypatch):
    class Page:
        def extract_text(self, **kwargs):
            assert not kwargs
            return "본문 한 줄\n다음 안내"

    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_a, **_kw: FakeReader([Page()]))
    assert PdfParser().parse(Path("normal.pdf")).text == "본문 한 줄\n다음 안내"
