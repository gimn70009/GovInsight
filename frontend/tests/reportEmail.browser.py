"""Render the production Java email HTML without sending messages or loading external URLs."""
import asyncio
import os
from pathlib import Path
from playwright.async_api import async_playwright, expect

OUT = Path(os.getenv('DELIVERY_SCREENSHOT_DIR', 'frontend/dist/delivery-checks'))
HTML = Path('backend/build/report-preview/email-brief.html').resolve()

async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport=dict(width=840, height=1050), device_scale_factor=1)
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        await page.route('http://**/*', lambda route: route.abort())
        await page.route('https://**/*', lambda route: route.abort())
        await page.goto(HTML.as_uri())
        await expect(page.locator('h2')).to_have_count(2)
        await expect(page.locator('h2').first).to_have_css('font-size', '22px')
        await expect(page.locator('h2').first).to_have_css('font-weight', '700')
        assert await page.locator('a').count() == 7
        await expect(page.locator('a').first).to_have_attribute('href', 'https://example.go.kr/file?file_id=form&part=1')
        assert await page.locator('script').count() == 0
        await page.screenshot(path=str(OUT / 'report-email-desktop.png'), full_page=True)
        first_card = page.locator('h2').first.locator('xpath=ancestor::table[1]')
        await first_card.screenshot(path=str(OUT / 'report-email-card.png'))
        for width in [390, 320]:
            await page.set_viewport_size(dict(width=width, height=844))
            assert await page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), f'Email overflow: {width}'
            await expect(page.locator('h2').first).to_be_visible()
            if width == 390:
                await page.screenshot(path=str(OUT / 'report-email-mobile.png'), full_page=True)
        assert not errors, errors
        await browser.close()
        print('PASS: production email HTML, article headings, seven preserved links, 390/320px overflow, no scripts or browser errors')

asyncio.run(main())
