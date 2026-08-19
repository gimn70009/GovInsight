from app.domains.monitoring.parsers.hwpx_parser import HwpxParser
from app.domains.monitoring.parsers.pdf_parser import PdfParser
from app.domains.monitoring.parsers.registry import AttachmentParserRegistry


def test_registry_selects_parser_by_case_insensitive_extension() -> None:
    registry = AttachmentParserRegistry()

    assert isinstance(registry.find("공고문.PDF"), PdfParser)
    assert isinstance(registry.find("공고문.hwpx"), HwpxParser)
    assert registry.find("공고문.hwp") is None
