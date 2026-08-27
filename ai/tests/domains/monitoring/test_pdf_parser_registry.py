from app.domains.monitoring.parsers.hwp_parser import HwpParser
from app.domains.monitoring.parsers.hwpx_parser import HwpxParser
from app.domains.monitoring.parsers.pdf_parser import PdfParser
from app.domains.monitoring.parsers.registry import AttachmentParserRegistry
from app.domains.monitoring.parsers.zip_parser import ZipParser


def test_registry_selects_parser_by_case_insensitive_extension() -> None:
    registry = AttachmentParserRegistry()

    assert isinstance(registry.find("공고문.PDF"), PdfParser)
    assert isinstance(registry.find("공고문.HWP"), HwpParser)
    assert isinstance(registry.find("공고문.hwpx"), HwpxParser)
    assert isinstance(registry.find("서류.ZIP"), ZipParser)
