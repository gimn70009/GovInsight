"""Presentation validation with saved projections and mocked APIs; no model calls."""
import asyncio
import copy
import importlib.util
import json
import sys
from pathlib import Path

from playwright.async_api import async_playwright, expect

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "backend/build/participation-details-audit-20261117"
spec = importlib.util.spec_from_file_location(
    "quality_refresh", Path(__file__).with_name("documentDetailRefresh.browser.py"))
refresh = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = refresh
spec.loader.exec_module(refresh)


async def verify(browser, width, guide):
    h = await refresh.Harness(browser).start()
    try:
        await h.page.set_viewport_size({"width": width, "height": 1000})
        value = refresh.detail(pending=False, result=refresh.analysis("READY"))
        value["participationGuide"] = guide
        h.states[1] = value
        await h.open(analysis_tab=False)
        applicant = h.page.locator('[data-criterion="BUSINESS_FORM"]')
        await expect(applicant.locator('.participation-guide__rule')).to_have_count(4)
        await expect(applicant).not_to_contain_text("IRIS")
        preparation = h.page.get_by_role("region", name="접수 준비 안내")
        await expect(preparation).to_contain_text("IRIS")
        first = applicant.locator('.participation-guide__rule').first
        await first.locator('.participation-guide__condition-toggle').click()
        await expect(first.locator('.participation-guide__notes')).to_have_count(0)
        await expect(first.locator('.participation-guide__quality-notice')).to_be_visible()
        await expect(first.locator('blockquote')).to_be_visible()
        await expect(first.locator('blockquote')).to_contain_text('주무부처')
        await applicant.scroll_into_view_if_needed()
        assert await h.page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        await h.page.screenshot(path=str(OUT / f"quality-{width}.png"))
        assert not h.errors and not h.unexpected
    finally:
        await h.close()


async def readable(browser, guide):
    # A correctly formatted response must retain separate definitions and exceptions.
    guide = copy.deepcopy(guide)
    row = next(r for c in guide['categories'] for r in c['rows'] if r['code'] == 'BUSINESS_FORM')
    first = next(e for e in row['entries'] if e['rule']['section'] == 'BASIC')
    first['rule']['details'] = ['법 제2조에 따른 기관이 포함됩니다.', '다만 대학은 신청 대상에서 제외됩니다.']
    first['rule']['guidance'] = []
    h = await refresh.Harness(browser).start()
    try:
        value = refresh.detail(pending=False, result=refresh.analysis('READY'))
        value['participationGuide'] = guide
        h.states[1] = value
        await h.open(analysis_tab=False)
        item = h.page.locator('[data-criterion="BUSINESS_FORM"] .participation-guide__rule').first
        await item.locator('.participation-guide__condition-toggle').click()
        await expect(item.locator('.participation-guide__notes li')).to_have_count(2)
        await expect(item.locator('.participation-guide__quality-notice')).to_have_count(0)
        await expect(item.locator('blockquote')).to_have_count(0)
        await item.get_by_role('button', name='원문 근거 펼치기').click()
        await expect(item.locator('blockquote')).to_be_visible()
    finally:
        await h.close()


async def main():
    guide = json.loads((OUT / 'projected-quality.json').read_text('utf-8'))
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel='msedge', headless=True)
        for width in (1440, 390, 320):
            await verify(browser, width, guide)
        await readable(browser, guide)
        await browser.close()
    print('Saved-result layout and readable-details checks passed at 1440/390/320px.')


if __name__ == '__main__':
    asyncio.run(main())
