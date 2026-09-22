"""Check production email checklist HTML locally; no external requests or sending."""
import asyncio
import os
from pathlib import Path
from playwright.async_api import async_playwright, expect

OUT = Path(os.getenv('DELIVERY_SCREENSHOT_DIR', 'frontend/dist/delivery-checks'))
HTML = Path('backend/build/report-preview/submission-checklist.html').resolve()

async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport=dict(width=840,height=1050),device_scale_factor=1)
        await page.route('http://**/*', lambda route: route.abort())
        await page.route('https://**/*', lambda route: route.abort())
        errors=[]
        page.on('pageerror',lambda error:errors.append(str(error)))
        await page.goto(HTML.as_uri())
        await expect(page.locator('[data-submission-card]')).to_have_count(0)
        await expect(page.locator('h2')).to_have_count(2)
        await expect(page.get_by_role('link',name='사업계획서 (ZIP)')).to_have_attribute('href','https://example.go.kr/download?id=1&seq=2')
        await expect(page.get_by_role('link',name='개인정보동의서 (ZIP)')).to_be_visible()
        await expect(page.get_by_text('납세증명서(해당 시)',exact=True)).to_be_visible()
        await expect(page.get_by_text('준비 담당',exact=False)).to_have_count(0)
        assert await page.locator('a').count()==5
        assert await page.locator('script').count()==0
        await page.screenshot(path=str(OUT/'submission-email-desktop.png'),full_page=True)
        await page.locator('h2').first.locator('xpath=ancestor::table[1]').screenshot(path=str(OUT/'submission-email-card.png'))
        for width in [390,320]:
            await page.set_viewport_size(dict(width=width,height=844))
            assert await page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
            if width==390:
                await page.screenshot(path=str(OUT/'submission-email-mobile.png'),full_page=True)
        assert not errors, errors
        await browser.close()
        print('PASS: short document names, ZIP URLs, no owner metadata, no 390/320px overflow')

asyncio.run(main())
