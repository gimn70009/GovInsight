"""Explicit offline browser tests: HWP_RENDERER_INTEGRATION=1 after installing assets."""

import os
from pathlib import Path

import pytest
from pypdf import PdfReader

from app.domains.monitoring.parsers.hwp_parser import HwpParser
from app.domains.monitoring.parsers.hwp_pdf_converter import HwpPdfConverter
from app.domains.monitoring.parsers.hwpx_parser import HwpxParser

pytestmark = pytest.mark.skipif(
    os.getenv("HWP_RENDERER_INTEGRATION") != "1",
    reason="Requires explicitly installed WASM data files and existing Chromium",
)

FIXTURES = Path(__file__).parent / "fixtures" / "hwp-renderer"


@pytest.mark.parametrize("extension", ["hwp", "hwpx"])
def test_real_original_layout_conversion_preserves_table_text(extension):
    import io

    source = FIXTURES / ("paired." + extension)
    converted = HwpPdfConverter().convert(source)
    assert converted.pdf.startswith(b"%PDF")
    assert len(PdfReader(io.BytesIO(converted.pdf)).pages) == len(converted.page_texts)
    assert "A11" in "\n".join(converted.page_texts)
    assert "B22" in "\n".join(converted.page_texts)
    text = "\n".join(converted.page_texts)
    assert all(label in text for label in ("A11", "A12", "A21", "A22", "B11", "B12",
                                          "B21", "B22", "C11"))


@pytest.mark.parametrize("extension", ["hwp", "hwpx"])
def test_real_conversion_to_mock_openai_reuses_success_cache(monkeypatch, extension):
    import base64
    import io
    import json

    import httpx

    from app.domains.monitoring.parsers import vlm_ocr
    from app.domains.monitoring.parsers.hwp_vlm_parser import HwpVlmParser
    from app.domains.monitoring.parsers.pdf_parser import PdfParser
    from app.domains.monitoring.parsers.vlm_config import VlmOcrSettings

    requests = []

    def respond(request):
        requests.append(request)
        payload = json.loads(request.content)
        data = payload["input"][0]["content"][0]["file_data"].split(",", 1)[1]
        page = PdfReader(io.BytesIO(base64.b64decode(data))).pages[0]
        text = PdfParser()._extract_page_text(page)
        return httpx.Response(200, json={
            "status": "completed",
            "output": [{"type": "message", "status": "completed", "content": [{
                "type": "output_text",
                "text": json.dumps({"complete": True, "blocks": [
                    {"kind": "text", "text": text, "cells": []},
                ]}),
            }]}],
        })

    # Fixture tables contain synthetic IDs, not real notice-topic headings.
    monkeypatch.setattr(vlm_ocr, "select_ocr_pages", lambda *a: [0])
    vlm_ocr._CACHE.clear()
    ocr = vlm_ocr.VlmOcr(
        VlmOcrSettings(api_key="mock-only"), transport=httpx.MockTransport(respond),
    )
    source = FIXTURES / ("paired." + extension)
    parser = HwpVlmParser(HwpParser() if extension == "hwp" else HwpxParser(), ocr)
    first, second = parser.parse(source), parser.parse(source)
    assert first.warning is None and second.warning is None
    assert "A11" in first.text and "B22" in second.text
    assert len(requests) == ocr.calls == 1
    vlm_ocr._CACHE.clear()


def test_wasm_conversion_with_new_python_native_loads_blocked():
    import subprocess
    import sys

    script = r'''
import importlib.machinery
from pathlib import Path
from fastapi.testclient import TestClient
from app.main import app

with TestClient(app) as client:
    assert client.get("/health").status_code == 200
    def reject(self, spec):
        raise ImportError("Blocked new native extension: " + spec.name)
    importlib.machinery.ExtensionFileLoader.create_module = reject
    from app.domains.monitoring.parsers.hwp_pdf_converter import HwpPdfConverter
    root = Path("tests/domains/monitoring/fixtures/hwp-renderer")
    converter = HwpPdfConverter()
    for extension in ("hwp", "hwpx"):
        result = converter.convert(root / ("paired." + extension))
        assert "A11" in "\n".join(result.page_texts)
        assert result.pdf.startswith(b"%PDF")
    assert client.get("/health").json() == {"status": "UP"}
'''
    result = subprocess.run(
        [sys.executable, "-c", script], cwd=Path(__file__).resolve().parents[3],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60,
    )
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.parametrize("split_batches", [False, True])
def test_inline_pages_keep_their_own_clip_regions(monkeypatch, split_batches):
    from playwright.async_api import Page

    evaluate = Page.evaluate
    svgs = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">'
        '<defs><clipPath id="cell-clip-1"><rect width="200" height="60" y="0"/>'
        '</clipPath></defs><g clip-path="url(#cell-clip-1)">'
        '<text x="10" y="30" font-size="20">TOP</text></g></svg>',
        '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">'
        '<defs><clipPath id="cell-clip-1"><rect width="200" height="60" y="100"/>'
        '</clipPath></defs><g clip-path="url(#cell-clip-1)">'
        '<text x="10" y="130" font-size="20">BOTTOM</text></g></svg>',
    ]

    async def render(self, expression, arg=None):
        if expression == "(i) => doc.renderPageSvg(i)":
            return svgs[arg]
        return await evaluate(self, expression, arg)

    from app.domains.monitoring.parsers import hwp_pdf_converter as converter

    batch_sizes = []
    print_batch = converter._print_batch

    async def record_batch(page, pages, styles):
        batch_sizes.append(len(pages))
        return await print_batch(page, pages, styles)

    if split_batches:
        limit = max(len(converter._sanitize_svg(svg)[0].encode()) for svg in svgs)
        monkeypatch.setattr(converter, "MAX_SVG_BATCH_BYTES", limit)
    monkeypatch.setattr(converter, "_print_batch", record_batch)
    monkeypatch.setattr(Page, "evaluate", render)
    converted = HwpPdfConverter().convert(FIXTURES / "paired.hwpx")
    assert batch_sizes == ([1, 1] if split_batches else [2])
    assert len(converted.page_texts) == 2
    assert "TOP" in converted.page_texts[0]
    assert "BOTTOM" in converted.page_texts[1]



def test_embedded_svg_with_text_gradient_mask_and_use_renders(monkeypatch):
    import base64

    from playwright.async_api import Page

    evaluate = Page.evaluate

    async def render(self, expression, arg=None):
        if expression == "(i) => doc.renderPageSvg(i)":
            label = "ELIGIBILITY" if arg == 0 else "DOCUMENTS"
            body = (
                '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">'
                '<defs><linearGradient id="paint"><stop offset="0" stop-color="white"/>'
                '<stop offset="1" stop-color="yellow"/></linearGradient>'
                '<mask id="cut"><rect width="200" height="200" fill="white"/></mask>'
                '<path id="line" d="M10 100 L150 100" stroke="black"/></defs>'
                '<g mask="url(#cut)"><rect width="200" height="200" fill="url(#paint)"/>'
                f'<use href="#line"/><text x="10" y="40" font-size="18">{label}</text>'
                '</g></svg>'
            )
            encoded = base64.b64encode(body.encode()).decode()
            return (
                '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">'
                f'<image width="200" height="200" href="data:image/svg+xml;base64,{encoded}"/>'
                '</svg>'
            )
        return await evaluate(self, expression, arg)

    monkeypatch.setattr(Page, "evaluate", render)
    converted = HwpPdfConverter().convert(FIXTURES / "paired.hwpx")
    assert len(converted.page_texts) == 2
    # PDF text extraction may split glyph runs even when the image renders correctly.
    assert "ELIGIBILITY" in "".join(converted.page_texts[0].split())
    assert "DOCUMENTS" in "".join(converted.page_texts[1].split())



def test_total_svg_limit_still_rejects_across_print_batches(monkeypatch):
    from app.domains.monitoring.parsers import hwp_pdf_converter as converter

    monkeypatch.setattr(converter, "MAX_TOTAL_SVG_BYTES", 1)
    with pytest.raises(ValueError, match="Rendered document too large"):
        HwpPdfConverter().convert(FIXTURES / "paired.hwpx")



def test_single_sanitized_page_cannot_exceed_batch_size_limit(monkeypatch):
    from app.domains.monitoring.parsers import hwp_pdf_converter as converter

    monkeypatch.setattr(converter, "MAX_SVG_BATCH_BYTES", 1)
    with pytest.raises(ValueError, match="Rendered page batch too large"):
        HwpPdfConverter().convert(FIXTURES / "paired.hwpx")
