import base64
import hashlib
import io
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import pytest
from pypdf import PdfWriter

from app.domains.monitoring.parsers.base import ParsedAttachment
from app.domains.monitoring.parsers.hwp_pdf_converter import ConvertedHwp, _sanitize_svg
from app.domains.monitoring.parsers.hwp_vlm_parser import HwpVlmParser
from app.domains.monitoring.parsers.vlm_config import VlmOcrSettings


def converted():
    writer = PdfWriter()
    writer.add_blank_page(595, 842)
    writer.add_blank_page(595, 842)
    stream = io.BytesIO()
    writer.write(stream)
    return ConvertedHwp(stream.getvalue(), ["제출서류\n신청서\n필수", "신청자격\n중소기업"])


class Local:
    def parse(self, path):
        return ParsedAttachment(text="제출서류\n신청서\t필수\n신청자격\n중소기업")


class FakeOcr:
    settings = VlmOcrSettings(api_key="test")
    calls = 0
    files = 0
    document_key = "test"

    def enhance(self, reader, pages):
        assert len(reader.pages) == 2
        self.calls += 2
        return ["제출서류\n[표]\n서류명\t비고\n신청서\t필수\n[/표]", pages[1]], None


def test_hwp_converts_original_and_uses_same_ocr_contract():
    called = []
    converter = SimpleNamespace(convert=lambda path: called.append(path) or converted())
    ocr = FakeOcr()
    parsed = HwpVlmParser(Local(), ocr, converter).parse(Path("sample.hwp"))
    assert called == [Path("sample.hwp")] and ocr.calls == 2
    assert "[표]" in parsed.text and "신청자격\n중소기업" in parsed.text
    assert parsed.warning is None


def test_missing_runtime_dll_policy_or_conversion_timeout_preserves_local_result():
    for error in [OSError("DLL private contents"), TimeoutError("private text"),
                  ValueError("font unavailable")]:
        def fail(path):
            raise error
        ocr = FakeOcr()
        parsed = HwpVlmParser(Local(), ocr, SimpleNamespace(convert=fail)).parse(Path("a.hwpx"))
        assert parsed.text == Local().parse(None).text
        assert parsed.warning and "private" not in parsed.warning
        assert ocr.calls == 0


def test_imperfect_pdf_text_does_not_block_visual_ocr():
    result = replace(converted(), page_texts=["제출서류\n신신청청서서\n필필수수", ""])
    ocr = FakeOcr()
    parsed = HwpVlmParser(
        Local(), ocr, SimpleNamespace(convert=lambda p: result),
    ).parse(Path("a.hwp"))
    assert ocr.calls == 2 and parsed.warning is None
    assert parsed.text.startswith("[PDF 1페이지 시각 판독]")
    assert "신청서\t필수" in parsed.text
    assert parsed.text.endswith(Local().parse(None).text)


def test_partial_visual_reading_keeps_full_local_text():
    ocr = FakeOcr()
    ocr.enhance = lambda reader, pages: (["제출서류\n신청서 필수", ""], "다른 페이지 한도")
    result = HwpVlmParser(
        Local(), ocr, SimpleNamespace(convert=lambda p: converted()),
    ).parse(Path("a.hwp"))
    assert result.text.startswith("[PDF 1페이지 시각 판독]\n제출서류\n신청서 필수")
    assert result.text.endswith(Local().parse(None).text)
    assert "[PDF 2페이지 시각 판독]" not in result.text
    assert result.warning == "다른 페이지 한도"


@pytest.mark.parametrize("state", ["disabled", "missing-key", "budget", "files"])
def test_no_conversion_without_enabled_key_and_available_budget(state):
    def unexpected(path):
        pytest.fail("unnecessary conversion")
    ocr = FakeOcr()
    if state == "disabled":
        ocr.settings = replace(ocr.settings, enabled=False)
    elif state == "missing-key":
        ocr.settings = replace(ocr.settings, api_key="")
    elif state == "files":
        ocr.files = 2
    else:
        ocr.calls = 4
    result = HwpVlmParser(Local(), ocr, SimpleNamespace(convert=unexpected)).parse(Path("a.hwp"))
    assert result.text == Local().parse(None).text


def test_no_visual_result_keeps_native_table_structure():
    ocr = FakeOcr()
    ocr.enhance = lambda reader, pages: (pages, None)
    result = HwpVlmParser(
        Local(), ocr, SimpleNamespace(convert=lambda p: converted()),
    ).parse(Path("a.hwp"))
    assert result.text == Local().parse(None).text and result.warning is None


@pytest.mark.parametrize("body", [
    '<script>alert(1)</script>',
    '<foreignObject><div>active</div></foreignObject>',
    '<text onclick="alert(1)">text</text>',
    '<image href="https://external.invalid/image.png"/>',
    '<text style="fill:url(https://external.invalid/a)">x</text>',
    '<image href="data:image/svg+xml;base64,PHN2Zz4="/>',
])
def test_active_or_network_svg_content_is_rejected(body):
    with pytest.raises(ValueError):
        _sanitize_svg(f'<svg width="794" height="1123">{body}</svg>')


def test_valid_svg_keeps_text_and_uses_local_korean_font():
    svg, text, width, height = _sanitize_svg(
        '<svg xmlns="http://www.w3.org/2000/svg" width="794px" height="1123">'
        '<text font-family="unknown">신청서<tspan> 필수</tspan></text></svg>'
    )
    assert text == "신청서 필수" and "GovInsightKorean" in svg
    assert (width, height) == (794, 1123)


def test_registry_shares_one_ocr_budget_across_all_formats(monkeypatch, tmp_path):
    from app.domains.monitoring.parsers import hwp_vlm_parser, registry, vlm_config, vlm_ocr
    from tests.domains.monitoring.test_pdf_parser_registry import hwpx_bytes

    service = FakeOcr()
    monkeypatch.setattr(vlm_config.VlmOcrSettings, "from_env", lambda: service.settings)
    monkeypatch.setattr(vlm_ocr, "VlmOcr", lambda settings, **kwargs: service)
    used = []

    class Parser:
        def __init__(self, local_parser, ocr):
            used.append(ocr)
            self.local = local_parser

        def parse(self, path):
            return self.local.parse(path)

    monkeypatch.setattr(hwp_vlm_parser, "HwpVlmParser", Parser)
    parsers = registry.AttachmentParserRegistry()
    for name in ["a.hwp", "b.hwpx"]:
        path = tmp_path / name
        path.write_bytes(hwpx_bytes("본문"))
        parsers.find(name).parse(path)
    # Mislabeled HWP content is correctly routed to the same HWPX wrapper.
    pdf = parsers.find("file.pdf").parsers[".pdf"].get_parser()
    assert used == [service, service] and pdf.ocr is service



def embedded_svg_image(body):
    data = base64.b64encode(body.encode()).decode()
    return f'<image href="data:image/svg+xml;base64,{data}"/>'


def test_static_embedded_vector_image_keeps_geometry():
    from xml.etree import ElementTree as ET

    body = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 474 477">'
            '<polygon fill="#FF00C9" points="0,0 212,0 212,216 0,216"/></svg>')
    result, _, _, _ = _sanitize_svg(
        '<svg width="794" height="1123">' + embedded_svg_image(body) + '</svg>',
    )
    image = next(node for node in ET.fromstring(result).iter()
                 if node.tag.rsplit("}", 1)[-1] == "image")
    nested = ET.fromstring(base64.b64decode(image.get("href").split(",", 1)[1]))
    polygon = next(node for node in nested.iter() if node.tag.rsplit("}", 1)[-1] == "polygon")
    assert polygon.get("fill") == "#FF00C9"
    assert polygon.get("points") == "0,0 212,0 212,216 0,216"


@pytest.mark.parametrize("body", [
    '<svg><script>alert(1)</script></svg>',
    '<svg><foreignObject><div>active</div></foreignObject></svg>',
    '<svg><rect onclick="alert(1)"/></svg>',
    '<svg><image href="https://external.invalid/pixel"/></svg>',
    '<svg><rect fill="url(https://external.invalid/paint)"/></svg>',
    '<svg><animate attributeName="href"/></svg>',
    '<!DOCTYPE svg [<!ENTITY content "text">]><svg/>',
])
def test_embedded_vector_rejects_active_or_external_content(body):
    with pytest.raises(ValueError):
        _sanitize_svg('<svg width="794" height="1123">' + embedded_svg_image(body) + '</svg>')


def test_conversion_failure_log_identifies_safe_reason_and_file(tmp_path, parser_logs):
    path = tmp_path / "private-filename.hwpx"
    path.write_bytes(b"document contents")

    def fail(path):
        raise ValueError("External SVG reference")

    result = HwpVlmParser(Local(), FakeOcr(), SimpleNamespace(convert=fail)).parse(path)
    assert result.text == Local().parse(None).text and result.warning
    assert "code=external_svg_reference" in parser_logs.text
    assert "document_key=test" in parser_logs.text
    assert "file_key=" + hashlib.sha256(path.read_bytes()).hexdigest()[:12] in parser_logs.text
    assert "private-filename" not in parser_logs.text
    assert "document contents" not in parser_logs.text


def test_unknown_conversion_error_body_is_not_logged(parser_logs):
    def fail(path):
        raise ValueError("private document or provider error contents")

    HwpVlmParser(Local(), FakeOcr(), SimpleNamespace(convert=fail)).parse(Path("missing.hwpx"))
    assert "code=unclassified_error" in parser_logs.text
    assert "private document" not in parser_logs.text


@pytest.fixture
def parser_logs(caplog):
    from app.domains.monitoring.parsers.hwp_vlm_parser import logger

    logger.addHandler(caplog.handler)
    caplog.set_level("INFO", logger=logger.name)
    try:
        yield caplog
    finally:
        logger.removeHandler(caplog.handler)



def test_embedded_svg_preserves_text_masks_filters_styles_and_internal_use():
    from xml.etree import ElementTree as ET

    body = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
      <defs>
        <linearGradient id="paint"><stop offset="0" stop-color="red"/></linearGradient>
        <mask id="cut"><rect width="100" height="100" fill="white"/></mask>
        <filter id="blur"><feGaussianBlur stdDeviation="1"/></filter>
        <path id="shape" d="M0 0 L30 30"/>
      </defs>
      <g style="fill: url('#paint'); mask: url(#cut); filter: url(#blur)">
        <use href="#shape"/><text x="2" y="20">신청자격<tspan>중소기업</tspan></text>
      </g>
    </svg>"""
    result, _, _, _ = _sanitize_svg(
        '<svg width="794" height="1123">' + embedded_svg_image(body) + '</svg>',
    )
    image = next(node for node in ET.fromstring(result).iter()
                 if node.tag.rsplit("}", 1)[-1] == "image")
    nested = ET.fromstring(base64.b64decode(image.get("href").split(",", 1)[1]))
    assert "신청자격중소기업" in "".join(nested.itertext())
    tags = {node.tag.rsplit("}", 1)[-1] for node in nested.iter()}
    assert {"mask", "filter", "feGaussianBlur", "use", "text", "tspan"} <= tags
    group = next(node for node in nested.iter() if node.tag.rsplit("}", 1)[-1] == "g")
    assert "url(#embedded1-paint)" in group.get("style")
    use = next(node for node in nested.iter() if node.tag.rsplit("}", 1)[-1] == "use")
    assert use.get("href") == "#embedded1-shape"


def test_embedded_svg_nesting_is_supported_but_bounded():
    body = '<svg viewBox="0 0 100 100"><circle cx="20" cy="20" r="5"/></svg>'
    for _ in range(3):
        body = '<svg width="794" height="1123">' + embedded_svg_image(body) + '</svg>'
        _sanitize_svg(body)
    with pytest.raises(ValueError, match="nesting limit"):
        _sanitize_svg('<svg width="794" height="1123">' + embedded_svg_image(body) + '</svg>')


def test_nested_svg_external_reference_is_still_rejected():
    body = '<svg><image href="https://external.invalid/pixel"/></svg>'
    for _ in range(2):
        body = '<svg>' + embedded_svg_image(body) + '</svg>'
    with pytest.raises(ValueError, match="External SVG reference"):
        _sanitize_svg('<svg width="794" height="1123">' + embedded_svg_image(body) + '</svg>')


@pytest.mark.parametrize("body", [
    '<svg><animateMotion/></svg>',
    '<svg><animateTransform/></svg>',
    '<svg xmlns:html="http://www.w3.org/1999/xhtml"><html:script>bad()</html:script></svg>',
    '<svg><g xml:base="https://external.invalid"><use href="#item"/></g></svg>',
    '<svg><rect style="fill:url(#paint);filter:url(https://external.invalid/a)"/></svg>',
])
def test_common_svg_policy_rejects_active_elements_and_external_styles(body):
    with pytest.raises(ValueError):
        _sanitize_svg('<svg width="794" height="1123">' + embedded_svg_image(body) + '</svg>')



def test_pdf_batch_total_size_limit_does_not_keep_oversized_part(monkeypatch):
    from app.domains.monitoring.parsers import hwp_pdf_converter as converter

    monkeypatch.setattr(converter, "MAX_PDF_BYTES", 10)
    parts = []
    size = converter._append_pdf_part(parts, b"123456", 0)
    with pytest.raises(ValueError, match="Converted PDF too large"):
        converter._append_pdf_part(parts, b"12345", size)
    assert parts == [b"123456"]


def test_pdf_batch_merge_preserves_page_order_and_dimensions(monkeypatch):
    from pypdf import PdfReader

    from app.domains.monitoring.parsers import hwp_pdf_converter as converter

    parts = []
    for width, height in [(123, 456), (234, 567)]:
        with PdfWriter() as writer, io.BytesIO() as output:
            writer.add_blank_page(width=width, height=height)
            writer.write(output)
            parts.append(output.getvalue())
    merged = converter._merge_pdf_parts(parts)
    reader = PdfReader(io.BytesIO(merged))
    assert [(page.mediabox.width, page.mediabox.height) for page in reader.pages] == [
        (123, 456), (234, 567),
    ]
    monkeypatch.setattr(converter, "MAX_PDF_BYTES", len(merged) - 1)
    with pytest.raises(ValueError, match="Converted PDF too large"):
        converter._merge_pdf_parts(parts)
