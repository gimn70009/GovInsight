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
                return (
                    "주관기관   공동기관\n중소중견   제한없음\n문의처\n사업팀 02-0000-0000\n" * 10
                )
            return "주관기관공동기관중소중견제한없음문의처사업팀02-0000-0000" * 10

    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_a, **_kw: FakeReader([TablePage()]))
    result = PdfParser().parse(Path("table.pdf"))
    assert "주관기관\t공동기관\n중소중견\t제한없음" in result.text
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


@pytest.mark.parametrize(
    "flattened",
    [
        "문의처 전화053-000-111102-000-2222",
        "문의처 이메일help@agency.or.krdesk@society.or.kr",
    ],
)
def test_short_contact_table_uses_layout_even_below_generic_collapse_threshold(
    monkeypatch,
    flattened,
):
    class Page:
        def extract_text(self, **kwargs):
            if kwargs.get("extraction_mode") == "layout":
                restored = flattened.replace("111102", "1111\n02").replace("krdesk", "kr\ndesk")
                return "문의 가능 시간: 평일 10:00~17:00\n" + restored + "\n점심시간 제외"
            return "문의 가능 시간: 평일 10:00~17:00\n" + flattened + "\n점심시간 제외"

    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_a, **_kw: FakeReader([Page()]))
    text = PdfParser().parse(Path("contacts.pdf")).text
    if "전화" in flattened:
        assert "전화053-000-1111\n02-000-2222" in text
    else:
        assert "help@agency.or.kr\ndesk@society.or.kr" in text
    assert "점심시간 제외" in text


def test_contact_layout_failure_preserves_plain_text(monkeypatch):
    text = "문의처 전화053-000-111102-000-2222"

    class Page:
        def extract_text(self, **kwargs):
            if kwargs:
                raise NotImplementedError("layout unavailable")
            return text

    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_a, **_kw: FakeReader([Page()]))
    assert PdfParser().parse(Path("contacts.pdf")).text == text


def test_layout_missing_rotated_exception_keeps_original_text(monkeypatch):
    plain = "제출서류\n서류명 필수 비고\n사업계획서 필수\n단, 선정 이후 제출"

    class Page:
        def extract_text(self, **kwargs):
            return "서류명    필수    비고\n사업계획서    필수" if kwargs else plain

    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_a, **_kw: FakeReader([Page()]))
    assert PdfParser().parse(Path("rotated.pdf")).text == plain


def test_short_submission_table_preserves_aligned_columns(monkeypatch):
    plain = "제출서류\n서류명 비고\n신청서 필수\n확인서 해당 시"

    class Page:
        def extract_text(self, **kwargs):
            if kwargs:
                return "제출서류\n서류명    비고\n신청서    필수\n확인서    해당 시"
            return plain

    monkeypatch.setattr(pdf_parser, "PdfReader", lambda *_a, **_kw: FakeReader([Page()]))
    assert "신청서\t필수\n확인서\t해당 시" in PdfParser().parse(Path("table.pdf")).text
