"""Shared loading/empty cards: no contradictory empty state or extra source action.
Uses mocked reads only; never starts extraction or paid model requests.
"""
import asyncio
import importlib.util
from pathlib import Path
from playwright.async_api import async_playwright, expect

spec = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("businessSummary.browser.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)
OUT = Path("frontend/dist/section-status-checks")


def detail(status="PENDING", populated=False):
    value = summary.ready()
    documents = value["analysis"]["proposal"]["preparation"]["submissionDocuments"][:1] if populated else []
    value["submissionGuide"] = dict(documents=documents, notes=[], notesStatus=status)
    text = "중소기업이 신청할 수 있습니다."
    source = dict(origin="NOTICE_BODY", excerpt=text)
    entry = dict(displayStatus="VERIFIED", rule=dict(section="BASIC", label="신청 주체", text=text,
                 scope=None, audience=None, details=[], exceptions=[], guidance=[], outcomes=[], sources=[source]))
    categories = [dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[
        dict(code="BUSINESS_FORM", label="신청 주체", status="STATED", entries=[entry])])] if populated else []
    value["participationGuide"] = dict(conditions=[], rules=[], variants=[], categories=categories, rewriteStatus=status)
    return value


async def check(browser, width):
    h = await summary.refresh.Harness(browser).start()
    try:
        await h.page.set_viewport_size(dict(width=width, height=1000))
        await h.page.emulate_media(reduced_motion="reduce")
        h.states[1] = detail()
        await h.open(analysis_tab=False)
        regions = [h.page.get_by_role("region", name=name, exact=True) for name in ("제출서류", "신청 자격")]
        for region in regions:
            await expect(region.locator(".section-status--loading")).to_have_count(1)
            await expect(region.locator(".section-status--empty")).to_have_count(0)
            await expect(region.get_by_role("status")).to_have_attribute("aria-live", "polite")
            assert await region.locator(".section-status__spinner").evaluate("el => getComputedStyle(el).animationName") == "none"
        styles = [await region.locator(".section-status").evaluate("el => {const s=getComputedStyle(el);return [s.padding,s.backgroundColor,s.borderRadius]}") for region in regions]
        assert styles[0] == styles[1], styles
        await regions[1].screenshot(path=str(OUT / f"loading-{width}.png"))
        h.states[1] = detail(populated=True)
        await h.tick()
        for region in regions:
            await expect(region.locator(".section-status--compact")).to_have_count(1)
        await expect(regions[0].get_by_role("table")).to_be_visible()
        await expect(regions[1].locator(".participation-guide__group")).to_be_visible()
        await regions[0].screenshot(path=str(OUT / f"partial-{width}.png"))
        h.states[1] = detail("READY")
        await h.tick()
        for region in regions:
            await expect(region.locator(".section-status--empty")).to_have_count(1)
            await expect(region.get_by_role("status")).to_have_count(0)
            await expect(region.locator(".section-status a, .section-status button")).to_have_count(0)
            assert await region.locator(".section-status").evaluate("el => el.scrollWidth <= el.clientWidth + 1")
        await regions[1].screenshot(path=str(OUT / f"empty-{width}.png"))
        await expect(h.page.get_by_role("link", name="공고 원문 보기", exact=True)).to_have_count(0)
        assert await h.page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        await h.stopped()
    finally:
        await h.close()


async def unavailable(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        h.states[1] = detail("UNAVAILABLE")
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        await expect(region.locator(".section-status--unavailable")).to_contain_text("신청 자격 확인을 완료하지 못했습니다")
        await expect(region.locator(".section-status a")).to_have_count(0)
        await h.stopped()
    finally:
        await h.close()


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        try:
            for width in (1440, 768, 390, 320):
                await check(browser, width)
            await unavailable(browser)
        finally:
            await browser.close()
    print("Shared cards: pending/partial/empty/unavailable, no extra action, reduced motion and 4 viewport checks passed")


if __name__ == "__main__":
    asyncio.run(main())
