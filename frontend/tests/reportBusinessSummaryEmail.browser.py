"""Verify the production summary-email HTML offline at desktop and mobile sizes."""
import asyncio
import math
from pathlib import Path

from playwright.async_api import async_playwright, expect

HTML = Path("backend/build/report-preview/email-business-summary.html").resolve()
OUT = Path("backend/build/report-preview")


async def main():
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch()
        try:
            page = await browser.new_page()
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            await page.route("http://**/*", lambda route: route.abort())
            await page.route("https://**/*", lambda route: route.abort())
            await page.goto(HTML.as_uri())
            cards = page.locator("[data-business-summary-card]")
            await expect(cards).to_have_count(3)
            await expect(page.locator("script,button,details,iframe")).to_have_count(0)
            await expect(page.locator("[data-report-institution]")).to_have_text(["중소벤처기업부", "국토교통부", "과학기술정보통신부"])
            await expect(page.locator("[data-report-overview]")).to_have_text("신규 1건 | 수정 1건 | 변경 없음 1건")
            for width in (1000, 768, 390, 320):
                await page.set_viewport_size({"width": width, "height": 1000})
                await page.evaluate("window.scrollTo(0, 0)")
                await expect(page.locator("[data-report-header]")).to_have_css("background-color", "rgb(25, 54, 85)")
                await expect(page.locator("h1")).to_have_css("color", "rgb(255, 255, 255)")
                for card in await cards.all():
                    rows = card.locator("[data-business-summary-row]")
                    await expect(rows).to_have_count(6)
                    assert await rows.locator("th").all_text_contents() == [
                        "사업 유형", "사업 담당기관", "사업명", "지원규모", "사업 설명", "제출기한"
                    ]
                    await expect(card.locator("[data-report-score]")).to_have_css("font-size", "12px")
                    await expect(card.locator("[data-report-change]")).to_be_visible()
                    await expect(card.locator("[data-report-institution]")).to_be_visible()
                    await expect(card.locator("h2")).to_be_visible()
                    for cell in await card.locator("[data-report-card-heading], [data-report-institution]").all():
                        assert await cell.evaluate("(el) => el.scrollWidth <= el.clientWidth + 1")
                    for cell in await rows.locator("th,td").all():
                        assert await cell.evaluate("(el) => el.scrollWidth <= el.clientWidth + 1")
                assert await page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
                first = cards.first
                await expect(first.locator("[data-report-score]")).to_have_text("기회점수 72점")
                await expect(first.locator("[data-report-score]")).to_have_css("background-color", "rgb(33, 95, 168)")
                await expect(page.locator("[data-organization-evidence]")).to_have_count(0)
                await expect(page.get_by_text("근거 원문", exact=True)).to_have_count(0)
                button = first.locator("[data-report-original-link]")
                await expect(button).to_have_css("background-color", "rgb(28, 66, 107)")
                await expect(button).to_have_css("color", "rgb(255, 255, 255)")
                await expect(button).to_have_css("text-decoration-line", "none")
                button_box = await button.bounding_box()
                table_box = await first.locator("[data-business-summary-table]").bounding_box()
                assert button_box["height"] >= 44
                assert abs(button_box["width"] - table_box["width"]) <= 2
                await expect(first.locator("[data-report-original-link]")).to_have_attribute(
                    "href", "https://example.org/notice?a=1&b=2")
                await expect(cards.nth(1).locator("[data-report-score]")).to_have_css("background-color", "rgb(71, 85, 105)")
                await expect(cards.nth(2).locator("[data-report-score]")).to_have_css("background-color", "rgb(185, 28, 28)")
                await expect(first.locator("[data-report-card-heading]")).to_have_css("background-color", "rgb(231, 239, 251)")
                await expect(first.locator("[data-report-card-heading]")).to_have_css("border-top-color", "rgb(53, 123, 216)")
                if width in (1000, 390, 320):
                    header_box = await page.locator("[data-report-header]").bounding_box()
                    card_box = await first.bounding_box()
                    await page.set_viewport_size({"width": width, "height": math.ceil(card_box["y"] + card_box["height"] + 24)})
                    await page.screenshot(path=str(OUT / f"email-business-summary-{width}.png"), clip={
                        "x": header_box["x"], "y": header_box["y"], "width": header_box["width"],
                        "height": card_box["y"] + card_box["height"] - header_box["y"],
                    })
                print(f"PASS {width}px: original report header and cards, source institutions, six rows, original button, no overflow")
            text = await page.locator("body").inner_text()
            assert "온라인 10월 30일 18시" in text and "원본 우편 11월 2일 도착분" in text
            assert "이전의 긴 보고서용 요약" not in text
            assert not errors, errors
        finally:
            await browser.close()


asyncio.run(main())
