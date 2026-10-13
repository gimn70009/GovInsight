"""Check production email sections, score palettes and local application links offline."""
import asyncio
import os
import re
from pathlib import Path

from playwright.async_api import async_playwright, expect

HTML = Path(os.getenv('REPORT_OPPORTUNITY_HTML', 'backend/build/report-preview/email-opportunity.html')).resolve()
OUT = Path('frontend/dist/delivery-checks')


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch()
        try:
            page = await browser.new_page(viewport={'width': 1000, 'height': 1000})
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            await page.route('http://**/*', lambda route: route.abort())
            await page.route('https://**/*', lambda route: route.abort())
            await page.goto(HTML.as_uri())
            cards = page.locator('[data-report-card]')
            assert await cards.count() >= 3
            await expect(page.locator('script')).to_have_count(0)
            body = await page.locator('body').inner_text()
            assert '핵심 요약' in body and '사업 핵심' not in body
            assert '유형 ·' not in body and '방식 ·' not in body
            assert await page.locator('[data-report-section="amount"]').count() >= 1
            assert await page.locator('[data-report-section="participation"]').count() >= 1
            assert await page.locator('[data-report-score]').count() == await cards.count()
            assert await page.locator('[data-report-classification]').count() == await cards.count()
            assert await page.locator('[data-report-amount-paragraph]').count() >= 1

            for card in await cards.all():
                badges = card.locator('[data-report-badges] > span')
                labels = await badges.all_text_contents()
                assert len(labels) == 3, labels
                await expect(card.locator('[data-report-category]')).to_have_count(1)
                await expect(card.locator('[data-report-status]')).to_have_count(1)
                sections = await card.locator('[data-report-section]').evaluate_all(
                    '(nodes) => nodes.map(node => node.dataset.reportSection)')
                if 'summary' in sections:
                    assert sections[0] == 'summary', sections
                scores = card.locator('[data-report-score]')
                if await scores.count():
                    score_label = await scores.first.inner_text()
                    assert labels[0] == score_label, labels
                    match = re.fullmatch(r'기회점수:\s*(\d{1,3})점', score_label)
                    score = int(match[1]) if match else None
                    expected = ('rgb(185, 28, 28)' if score is not None and 75 <= score <= 100
                                else 'rgb(33, 95, 168)' if score is not None and 40 <= score <= 74
                                else 'rgb(71, 85, 105)')
                    await expect(scores.first).to_have_css('background-color', expected)
                    await expect(scores.first).to_have_css('font-size', '16px')
                    sizes = await badges.evaluate_all("""nodes => nodes.map(node => {
                        const s=getComputedStyle(node);
                        return [s.fontSize, s.padding, s.lineHeight, s.borderRadius, node.getBoundingClientRect().height];
                    })""")
                    assert all(size == sizes[0] for size in sizes), sizes
                classification = card.locator('[data-report-classification]')
                await expect(classification).to_have_count(1)
                if await classification.count():
                    assert not any(label.startswith(('유형:', '방식:')) for label in labels), labels
                    for kind, label in (('type', '유형:'), ('method', '방식:')):
                        cells = classification.locator(f'[data-report-meta="{kind}"] > td')
                        await expect(cells).to_have_count(2)
                        await expect(cells.first).to_have_text(label)
                        assert await cells.first.evaluate('(node) => parseFloat(getComputedStyle(node).fontSize)') >= 14
                        assert await cells.nth(1).evaluate('(node) => parseFloat(getComputedStyle(node).fontSize)') >= 16
                        await expect(cells.nth(1)).to_be_visible()
                    sections = await card.locator('[data-report-section]').evaluate_all(
                        '(nodes) => nodes.map(node => node.dataset.reportSection)')
                    if 'amount' in sections and 'summary' in sections:
                        assert sections.index('summary') < sections.index('amount'), sections
                paragraphs = await card.locator('[data-report-amount-paragraph]').all_text_contents()
                normalized = [''.join(value.split()).rstrip('.') for value in paragraphs]
                assert len(normalized) == len(set(normalized)), paragraphs
                for paragraph in paragraphs:
                    assert not re.match(r'^\s*(?:[•○□■※*]|o\s+|\d+-\d+\.)', paragraph), paragraph
                for contact in await card.locator('[data-report-contact-group]').all():
                    await expect(contact.locator('[data-report-contact-label]')).to_be_visible()
                    value = await contact.inner_text()
                    assert not re.search(r'[▸▶▷□■※*]', value), value
                    assert (await contact.inner_text()).strip()
                for footer in await card.locator('[data-report-original]').all():
                    await expect(footer.locator('a')).to_have_count(1)
                    button = footer.locator('a[data-report-original-link]')
                    await expect(button).to_have_text('공고 원문 보기')
                    await expect(button).to_have_css('font-size', '16px')
                    assert await button.evaluate('(node) => parseFloat(getComputedStyle(node).paddingTop)') >= 10
                    assert (await button.get_attribute('href')).startswith(('http://', 'https://'))
                    assert '원문:' not in await footer.inner_text()
                    assert '접수 사이트' not in await footer.inner_text()
                for link in await card.locator('[data-report-section="submission"] a').all():
                    assert (await link.get_attribute('href')).startswith(('http://', 'https://'))
                    await expect(link).to_be_visible()

            for width in (1000, 390, 320):
                await page.set_viewport_size({'width': width, 'height': 1000})
                assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), width
                for card in await cards.all():
                    await expect(card.locator('h2')).to_be_visible()
                    for label in await card.locator('[data-report-contact-channel] > td:first-child').all():
                        if (await label.inner_text()).strip():
                            assert await label.evaluate('''node => {
                                const range = document.createRange();
                                range.selectNodeContents(node);
                                return range.getBoundingClientRect().height <= parseFloat(getComputedStyle(node).lineHeight) + 2;
                            }'''), await label.inner_text()
                    for cell in await card.locator('[data-report-contact-channel="phone"] [data-report-contact-value]').all():
                        phone = await cell.inner_text()
                        assert phone.count('(') == phone.count(')'), phone
                    if width >= 390:
                        for cell in await card.locator('[data-report-contact-channel="email"] [data-report-contact-value]').all():
                            if len((await cell.inner_text()).strip()) <= 22:
                                assert await cell.evaluate("""node => {
                                    const s = getComputedStyle(node);
                                    return node.clientHeight <= parseFloat(s.lineHeight)
                                        + parseFloat(s.paddingTop) + parseFloat(s.paddingBottom) + 2;
                                }"""), await cell.inner_text()
                await cards.first.screenshot(path=str(OUT / f'report-opportunity-card-{width}.png'))
            assert not errors, errors
            print('PASS: three consistent badges, summary first, structured contacts, equal badge sizes, distinct readable type/method rows, unchanged palettes, clean amount paragraphs, original button, inline routes, 1000/390/320px, no scripts or errors')
        finally:
            await browser.close()


asyncio.run(main())
