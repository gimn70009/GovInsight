"""Verify role choices and date meaning in production email HTML without sending mail."""
import asyncio
from pathlib import Path

from playwright.async_api import async_playwright, expect

HTML = Path("backend/build/report-preview/role-readability.html").resolve()
OUT = Path("frontend/dist/delivery-checks")


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch()
        try:
            page = await browser.new_page(viewport={"width": 1000, "height": 1000})
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            await page.route("http://**/*", lambda route: route.abort())
            await page.route("https://**/*", lambda route: route.abort())
            await page.goto(HTML.as_uri())
            await expect(page.locator("h2")).to_have_count(2)
            clear = page.locator("h2").first.locator("xpath=ancestor::table[1]")
            unclear = page.locator("h2").nth(1).locator("xpath=ancestor::table[1]")
            await expect(clear.locator("[data-report-role]")).to_have_count(2)
            await expect(clear.locator("[data-report-role]").first).to_contain_text("기업")
            await expect(clear.locator("[data-report-role]").nth(1)).to_contain_text("제한 없음")
            await expect(clear).to_contain_text("참여제한 기관은 제외합니다.")
            await expect(clear).to_contain_text("우편은 도착분만 유효합니다.")
            await expect(unclear.locator("[data-report-role]")).to_have_count(2)
            for role in await unclear.locator("[data-report-role]").all():
                await expect(role).to_contain_text("원문 확인 필요")
            for card in (clear, unclear):
                publication = card.locator('[data-report-fact="공고일"] > td > div').nth(1)
                await expect(publication).to_contain_text("2026년 10월 6일")
                await expect(publication).to_have_css("font-size", "16px")
                await expect(publication).to_have_css("font-weight", "400")
            deadline = clear.locator('[data-report-fact="제출·의견 기한"] > td > div').nth(1)
            await expect(deadline).to_contain_text("2026년 10월 30일 18:00")
            await expect(deadline).to_have_css("font-weight", "700")
            missing = unclear.locator('[data-report-fact="제출·의견 기한"] > td > div').nth(1)
            await expect(missing).to_have_text("원문 확인 필요")
            await expect(missing).to_have_css("font-weight", "400")
            text = await page.locator("body").inner_text()
            assert not any(token in text for token in ("□", "■", "국공립연", "외국법인"))
            await expect(page.get_by_role("link", name="온라인 신청")).to_have_attribute(
                "href", "https://example.org/apply"
            )
            await expect(page.locator("script")).to_have_count(0)
            for width in (1000, 390, 320):
                await page.set_viewport_size({"width": width, "height": 1000})
                assert await page.evaluate("document.documentElement.scrollWidth <= innerWidth")
                await page.screenshot(path=str(OUT / f"report-readability-{width}.png"), full_page=True)
                if width in (1000, 390):
                    await clear.screenshot(path=str(OUT / f"report-readability-card-{width}.png"))
            assert not errors, errors
        finally:
            await browser.close()
    print("PASS: role-specific checked choices, preserved exceptions, distinct publication/deadline, "
          "uncertain legacy eligibility, safe links and 1000/390/320px layouts")


asyncio.run(main())
