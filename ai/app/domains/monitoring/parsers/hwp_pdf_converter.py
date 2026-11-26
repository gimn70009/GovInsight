"""Render original HWP/HWPX layout in the existing Chromium, with no network access."""

import asyncio
import base64
import binascii
import io
import os
import re
import threading
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse
from xml.etree import ElementTree as ET

from pypdf import PdfReader, PdfWriter

from app.domains.monitoring.parsers.hwp_renderer_assets import verified_assets
from app.domains.monitoring.parsers.pdf_parser import PdfParser

MAX_INPUT_BYTES = 20 * 1024 * 1024
MAX_PDF_BYTES = 40 * 1024 * 1024
MAX_PAGES = 100
MAX_SVG_BYTES = 4 * 1024 * 1024
MAX_SVG_BATCH_BYTES = 20 * 1024 * 1024
MAX_TOTAL_SVG_BYTES = 100 * 1024 * 1024
ORIGIN = "https://govinsight-converter.invalid"
_CONVERSIONS = threading.BoundedSemaphore(1)

_CSP = (
    "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'; "
    "style-src 'unsafe-inline'; img-src data:; font-src 'self'; frame-src 'none';"
)


@dataclass(frozen=True)
class ConvertedHwp:
    pdf: bytes
    page_texts: list[str]


def _font_path() -> Path:
    configured = os.getenv("HWP_PDF_FONT_PATH", "").strip()
    if configured:
        return Path(configured)
    return Path(os.getenv("WINDIR", "C:/Windows")) / "Fonts" / "malgun.ttf"


class HwpPdfConverter:
    def convert(self, path: Path) -> ConvertedHwp:
        if path.stat().st_size > MAX_INPUT_BYTES:
            raise ValueError("HWP source too large")
        assets = verified_assets()
        font = _font_path().read_bytes()
        if not font or len(font) > 32 * 1024 * 1024:
            raise ValueError("Korean font unavailable")
        if not _CONVERSIONS.acquire(blocking=False):
            raise ValueError("HWP conversion capacity reached")
        try:
            return asyncio.run(self._convert(path.read_bytes(), assets, font))
        finally:
            _CONVERSIONS.release()

    async def _convert(self, source: bytes, assets: dict[str, bytes], font: bytes) -> ConvertedHwp:
        from playwright.async_api import async_playwright

        resources = {
            "/": ("text/html", b"<!doctype html><html><head></head><body></body></html>"),
            "/rhwp.mjs": ("text/javascript", assets["rhwp.js"]),
            "/rhwp.wasm": ("application/wasm", assets["rhwp_bg.wasm"]),
            "/font.ttf": ("font/ttf", font),
            "/source": ("application/octet-stream", source),
        }

        async def route(request):
            url = request.request.url
            entry = resources.get(urlparse(url).path) if url.startswith(ORIGIN + "/") else None
            if entry:
                mime, body = entry
                await request.fulfill(
                    content_type=mime, body=body,
                    headers={"Content-Security-Policy": _CSP, "Cache-Control": "no-store"},
                )
            else:
                await request.abort()

        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True, timeout=10_000)
            try:
                async with asyncio.timeout(45):
                    context = await browser.new_context(service_workers="block")
                    await context.route("**/*", route)
                    page = await context.new_page()
                    await page.goto(ORIGIN + "/")
                    count = await page.evaluate("""async () => {
                        const font = new FontFace('GovInsightKorean', 'url(/font.ttf)');
                        await font.load(); document.fonts.add(font);
                        const { default: init, HwpDocument } = await import('/rhwp.mjs');
                        await init({module_or_path: '/rhwp.wasm'});
                        const bytes = new Uint8Array(
                            await (await fetch('/source')).arrayBuffer());
                        window.doc = new HwpDocument(bytes);
                        return doc.pageCount();
                    }""")
                    if not isinstance(count, int) or not 1 <= count <= MAX_PAGES:
                        raise ValueError("Invalid rendered page count")
                    total_size = batch_size = pdf_size = 0
                    pages, styles, pdf_parts = [], [], []
                    for index in range(count):
                        svg = await page.evaluate("(i) => doc.renderPageSvg(i)", index)
                        clean, _, width, height = _sanitize_svg(svg, page_index=index)
                        # Bound both renderer output and sanitized markup. Print bounded
                        # batches so a long document does not require one huge HTML DOM.
                        size = max(len(svg.encode("utf-8")), len(clean.encode("utf-8")))
                        if size > MAX_SVG_BATCH_BYTES:
                            raise ValueError("Rendered page batch too large")
                        total_size += size
                        if total_size > MAX_TOTAL_SVG_BYTES:
                            raise ValueError("Rendered document too large")
                        if pages and batch_size + size > MAX_SVG_BATCH_BYTES:
                            part = await _print_batch(page, pages, styles)
                            pdf_size = _append_pdf_part(pdf_parts, part, pdf_size)
                            pages, styles, batch_size = [], [], 0
                        styles.append(f"@page p{index}{{size:{width}px {height}px;margin:0}}")
                        pages.append(
                            f'<div style="page:p{index};width:{width}px;'
                            f'height:{height}px;break-after:page">{clean}</div>'
                        )
                        batch_size += size
                    part = await _print_batch(page, pages, styles)
                    _append_pdf_part(pdf_parts, part, pdf_size)
                    await page.evaluate("doc.free(); delete window.doc;")
                    pdf = _merge_pdf_parts(pdf_parts)
                    reader = PdfReader(io.BytesIO(pdf))
                    if len(reader.pages) != count:
                        raise ValueError("PDF pagination changed")
                    # SVG may contain one <text> per glyph; use the actual PDF text
                    # as hints for topic selection, not as a fidelity requirement.
                    texts = [PdfParser()._extract_page_text(item) for item in reader.pages]
                    return ConvertedHwp(pdf=pdf, page_texts=texts)
            finally:
                await browser.close()


async def _print_batch(page, pages: list[str], styles: list[str]) -> bytes:
    # Only sanitized SVG enters the DOM. Replace the previous batch and its styles.
    await page.evaluate("""({html, style}) => {
        let css = document.getElementById('govinsight-print-style');
        if (!css) {
            css = document.createElement('style');
            css.id = 'govinsight-print-style';
            document.head.append(css);
        }
        css.textContent = style;
        document.body.innerHTML = html;
    }""", {
        "html": "".join(pages),
        "style": ("html,body{margin:0;padding:0}svg{display:block}"
                  "body>div:last-child{break-after:auto!important}" + "".join(styles)),
    })
    await page.evaluate("document.fonts.ready")
    pdf = await page.pdf(print_background=True, prefer_css_page_size=True)
    if len(pdf) > MAX_PDF_BYTES:
        raise ValueError("Converted PDF too large")
    if len(PdfReader(io.BytesIO(pdf)).pages) != len(pages):
        raise ValueError("PDF pagination changed")
    return pdf


def _append_pdf_part(parts: list[bytes], part: bytes, size: int) -> int:
    size += len(part)
    if size > MAX_PDF_BYTES:
        raise ValueError("Converted PDF too large")
    parts.append(part)
    return size


def _merge_pdf_parts(parts: list[bytes]) -> bytes:
    if len(parts) == 1:
        return parts[0]
    with PdfWriter() as writer, io.BytesIO() as output:
        for part in parts:
            writer.append(PdfReader(io.BytesIO(part)), import_outline=False)
        writer.write(output)
        pdf = output.getvalue()
    if len(pdf) > MAX_PDF_BYTES:
        raise ValueError("Converted PDF too large")
    return pdf


SVG_NAMESPACE = "http://www.w3.org/2000/svg"
MAX_EMBEDDED_SVG_DEPTH = 3
_LOCAL_URL = re.compile(r"url\s*\(\s*(['\"]?)#([\w.-]+)\1\s*\)", re.I)
_ANY_URL = re.compile(r"url\s*\([^)]*\)", re.I)


def _svg_root(data: bytes) -> ET.Element:
    if len(data) > MAX_SVG_BYTES or re.search(br"<!DOCTYPE|<!ENTITY", data, re.I):
        raise ValueError("Unsafe or oversized rendered SVG")
    root = ET.fromstring(data)
    if root.tag not in {"svg", "{" + SVG_NAMESPACE + "}svg"}:
        raise ValueError("Invalid SVG")
    return root


def _sanitize_svg(svg: str, *, page_index: int = 0) -> tuple[str, str, float, float]:
    root = _svg_root(svg.encode("utf-8"))
    width = _dimension(root.get("width", ""))
    height = _dimension(root.get("height", ""))
    text = _sanitize_svg_tree(root, prefix=f"p{page_index}-", depth=0)
    ET.register_namespace("", SVG_NAMESPACE)
    return ET.tostring(root, encoding="unicode"), text, width, height


def _sanitize_svg_tree(root: ET.Element, *, prefix: str, depth: int) -> str:
    """Apply one structural policy to page SVGs and embedded SVG images."""
    text = []
    for node in root.iter():
        if node.tag.startswith("{") and not node.tag.startswith("{" + SVG_NAMESPACE + "}"):
            raise ValueError("Foreign SVG namespace")
        tag = node.tag.rsplit("}", 1)[-1]
        if tag in {"script", "foreignObject", "iframe", "style", "a", "set", "discard"} or (
            tag.startswith("animate")
        ):
            raise ValueError("Active SVG content")
        for name, value in list(node.attrib.items()):
            local = name.rsplit("}", 1)[-1].lower()
            if local.startswith("on") or local == "base":
                raise ValueError("Active SVG attribute")
            if local in {"href", "src"}:
                if tag in {"image", "feImage"} and value.startswith("data:image/svg+xml;base64,"):
                    node.set(name, _embedded_svg_image(value, depth=depth + 1))
                elif not (
                    value.startswith("#") or tag in {"image", "feImage"}
                    and re.match(r"^data:image/(png|jpeg|gif|webp);base64,", value)
                ):
                    raise ValueError("External SVG reference")
            # Preserve ordinary inline styles and multiple internal paint references.
            # External URLs and incomplete url(...) expressions remain rejected.
            if re.search(r"url\s*\(", value, re.I):
                urls = list(_ANY_URL.finditer(value))
                if (any(not _LOCAL_URL.fullmatch(match[0]) for match in urls)
                        or re.search(r"url\s*\(", _ANY_URL.sub("", value), re.I)):
                    raise ValueError("External SVG style")
            if local == "style" and re.search(r"@import|@font-face", value, re.I):
                raise ValueError("External SVG style")
        if tag == "text":
            text.append("".join(node.itertext()))
        if tag in {"text", "tspan"}:
            node.set("font-family", "GovInsightKorean")
            node.set("style", node.get("style", "") + ";font-family:GovInsightKorean")
    # IDs in inline pages share a document. Embedded images have their own tree,
    # so rewrite each tree and its internal references together.
    ids = {node.get("id"): prefix + node.get("id")
           for node in root.iter() if node.get("id")}
    for node in root.iter():
        if node.get("id"):
            node.set("id", ids[node.get("id")])
        for name, value in list(node.attrib.items()):
            if name.rsplit("}", 1)[-1].lower() in {"href", "src"} and value.startswith("#"):
                node.set(name, "#" + ids.get(value[1:], value[1:]))
            elif _LOCAL_URL.search(value):
                node.set(name, _LOCAL_URL.sub(
                    lambda match: "url(#" + ids.get(match[2], match[2]) + ")", value,
                ))
    return "\n".join(text)


def _embedded_svg_image(value: str, *, depth: int) -> str:
    if depth > MAX_EMBEDDED_SVG_DEPTH:
        raise ValueError("Embedded SVG nesting limit")
    try:
        root = _svg_root(base64.b64decode(value.split(",", 1)[1], validate=True))
    except (binascii.Error, ET.ParseError) as exc:
        raise ValueError("Invalid embedded SVG") from exc
    _sanitize_svg_tree(root, prefix=f"embedded{depth}-", depth=depth)
    return "data:image/svg+xml;base64," + base64.b64encode(ET.tostring(root)).decode("ascii")


def _dimension(value: str) -> float:
    if not re.fullmatch(r"\d+(?:\.\d+)?(?:px)?", value):
        raise ValueError("Invalid SVG page size")
    number = float(value.removesuffix("px"))
    if not 10 <= number <= 2400:
        raise ValueError("SVG page size out of range")
    return number
