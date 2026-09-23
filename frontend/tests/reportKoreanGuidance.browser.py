"""Offline email and mocked web report readability checks; never sends messages."""
import asyncio
from pathlib import Path
from playwright.async_api import async_playwright, expect

BODY = Path('backend/src/test/resources/reports/korean-guidance.txt').read_text(encoding='utf-8')
OUT = Path('frontend/dist/delivery-checks')
BASE = 'http://127.0.0.1:4173'

async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport=dict(width=840, height=1050))
        failures = []
        page.on('pageerror', lambda e: failures.append(str(e)))
        await page.route('https://**/*', lambda route: route.abort())
        await page.goto(Path('backend/build/report-preview/korean-guidance.html').resolve().as_uri())
        await expect(page.locator('[data-report-fact-item]')).to_have_count(9)
        await expect(page.get_by_role('heading', name='문의 안내', exact=True)).to_have_count(1)
        await expect(page.locator('[data-report-note]')).to_have_count(0)
        await page.locator('h2').first.locator('xpath=ancestor::table[1]').screenshot(path=str(OUT/'korean-email-card.png'))
        for width in (390, 320):
            await page.set_viewport_size(dict(width=width, height=844))
            assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth')
            await page.screenshot(path=str(OUT/f'korean-email-{width}.png'), full_page=True)

        result = dict(status='SENT', recipientCount=1, sentCount=1, failedCount=0, errorMessage=None)
        report = dict(reportId=1, runId=7, title='한국어 제출 안내 보고서', createdAt='2026-09-22T08:00:00', generatedAt='2026-09-22T08:00:00', telegram=result, email=result)
        async def route_api(route):
            assert route.request.method == 'GET', 'No mutations are permitted'
            path = route.request.url.split('/api/')[1]
            if path == 'email/settings':
                data = dict(version=0, enabled=False, configured=False, provider='GMAIL', senderAddress='', senderName='', updatedAt=None, recipients=[])
            elif path == 'telegram/settings':
                data = dict(version=0, enabled=False, botConfigured=False, updatedAt=None, recipients=[])
            elif path.startswith('report-deliveries?'):
                data = dict(content=[report], totalPages=1, totalElements=1, page=0, size=10)
            elif path == 'report-deliveries/1':
                data = dict(report=report, body=BODY, telegramDeliveries=[], emailDeliveries=[])
            else:
                raise AssertionError(path)
            await route.fulfill(json=dict(isSuccess=True, data=data, message='OK'))
        await page.route('**/api/**', route_api)
        await page.add_init_script("sessionStorage.setItem('govinsight.accessToken','browser-test-placeholder')")
        await page.set_viewport_size(dict(width=1440, height=1050))
        await page.goto(BASE+'/reports')
        await page.add_style_tag(content='*,*::before,*::after {animation:none!important;transition:none!important}')
        await page.locator('.delivery-report-row').click()
        dialog = page.get_by_role('dialog', name='보고서 발송 상세', exact=True)
        await dialog.get_by_text('보고서 내용', exact=True).click()
        await expect(dialog.locator('.report-fact')).to_have_count(5)
        await expect(dialog.locator('.report-fact li')).to_have_count(9)
        await expect(dialog.locator('.report-body-note')).to_have_count(0)
        await expect(dialog.get_by_role('link', name='국문 연구개발계획서 (ZIP)', exact=True)).to_have_attribute('href', 'https://example.org/forms.zip')
        await dialog.screenshot(path=str(OUT/'korean-web-desktop.png'))
        for width in (390, 320):
            await page.set_viewport_size(dict(width=width, height=844))
            assert await dialog.evaluate('(el) => el.scrollWidth <= el.clientWidth')
            await dialog.screenshot(path=str(OUT/f'korean-web-{width}.png'))
        assert not failures, failures
        await browser.close()
        print('PASS: separate Korean facts, result contact heading, safe document link, desktop/390/320px email and web without overflow')

asyncio.run(main())
