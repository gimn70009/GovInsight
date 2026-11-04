"""Semantic notes: pending/terminal refresh, source disclosures and narrow layouts.
Run the backend SubmissionNotesProjectionTest first to create the cross-language fixture.
"""
import asyncio
import copy
import importlib.util
import json
import re
import sys
from pathlib import Path

from playwright.async_api import async_playwright, expect

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "notes_refresh", Path(__file__).with_name("documentDetailRefresh.browser.py"))
refresh = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = refresh
spec.loader.exec_module(refresh)
GUIDE = json.loads((ROOT / "backend/build/submission-notes-projected.json").read_text(encoding="utf-8"))
OUT = ROOT / "backend/build/submission-notes-browser"


def ready(status="READY"):
    value = refresh.detail(pending=False, result=refresh.analysis("READY"))
    value["submissionGuide"] = copy.deepcopy(GUIDE)
    value["submissionGuide"]["notesStatus"] = status
    if status in {"PENDING", "UNAVAILABLE"}:
        value["submissionGuide"]["notes"] = []
    value["attachments"] = [dict(attachmentId=1, fileName="공고문.pdf", fileExtension="pdf",
                                 fileSize=100, parseStatus="COMPLETED",
                                 downloadUrl="https://example.org/notice.pdf")]
    return value


async def states(browser):
    print("Checking pending and terminal states", flush=True)
    h = await refresh.Harness(browser).start()
    try:
        h.states[1] = ready("PENDING")
        await h.open(analysis_tab=False)
        await expect(h.page.get_by_text("제출 유의사항을 정리하고 있습니다.", exact=False)).to_be_visible()
        await expect(h.page.get_by_role("complementary", name="제출 유의사항", exact=True)).to_have_count(0)
        h.states[1] = ready()
        await h.tick()
        common = h.page.get_by_role("complementary", name="제출 유의사항", exact=True)
        await expect(common).to_be_visible()
        await expect(common).not_to_contain_text("1357")
        await expect(common).not_to_contain_text("3개월")
        row = h.page.get_by_role("table", name="제출서류 안내").locator("tbody tr").first
        await expect(row.locator(".business-summary__document-details")).to_be_hidden()
        await row.get_by_role("button", name=re.compile("해당 시 제출 조건 보기$")).click()
        await expect(row.locator(".business-summary__document-details")).to_be_visible()
        await expect(row).to_contain_text("3개월")
        await expect(row).to_contain_text("개인은 제출이 면제")
        await h.stopped()
    finally:
        await h.close()
    for status, message in [("PARTIAL", "일부 제출 조건을 확인하지 못했습니다."),
                            ("UNAVAILABLE", "제출 유의사항을 정리하지 못했습니다.")]:
        h = await refresh.Harness(browser).start()
        try:
            h.states[1] = ready(status)
            await h.open(analysis_tab=False)
            await expect(h.page.get_by_text(message, exact=False)).to_have_count(0)
            common = h.page.get_by_role("complementary", name="제출 유의사항", exact=True)
            if status == "PARTIAL":
                await expect(common).to_be_visible()
                await expect(common).to_contain_text("신청마감일 이후")
            else:
                await expect(common).to_have_count(0)
            await h.stopped()
        finally:
            await h.close()


async def layouts(browser):
    for width in (1440, 768, 390, 320):
        print(f"Checking layout {width}", flush=True)
        h = await refresh.Harness(browser).start()
        try:
            await h.page.set_viewport_size(dict(width=width, height=1000))
            h.states[1] = ready()
            await h.open(analysis_tab=False)
            common = h.page.get_by_role("complementary", name="제출 유의사항", exact=True)
            button = common.get_by_role("button", name=re.compile(" 원문 근거 확인$"))
            await button.focus()
            await h.page.keyboard.press("Enter")
            quote = next(n["source"]["excerpt"] for n in GUIDE["notes"] if n["appliesTo"] is None)
            await expect(common.locator("blockquote")).to_have_text(quote)
            await h.page.keyboard.press("Space")
            await expect(common.locator("blockquote")).to_be_hidden()
            await common.scroll_into_view_if_needed()
            assert await h.page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
            await h.page.screenshot(path=str(OUT / f"notes-{width}.png"), full_page=True)
        finally:
            await h.close()


async def main():
    OUT.mkdir(exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        try:
            await states(browser)
            await layouts(browser)
        finally:
            await browser.close()
    print("Submission notes refresh, scope, evidence, keyboard and 4 viewport checks passed")


asyncio.run(main())
