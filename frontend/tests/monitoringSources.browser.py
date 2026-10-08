"""Exercise the real settings UI with isolated API fixtures; never run collection."""
import asyncio
import json
import os
from pathlib import Path
from urllib.parse import urlparse

from playwright.async_api import async_playwright, expect

BASE = os.environ.get("SOURCE_PREVIEW_URL", "http://127.0.0.1:4187")
OUT = Path("frontend/dist/source-checks")
BOARDS = [
    ("산업통상부", "사업공고", "https://www.motir.go.kr/kor/article/ATCL2826a2625", "산업·통상·에너지 분야의 지원사업 및 사업공고를 수집합니다."),
    ("과학기술정보통신부", "사업공고", "https://www.msit.go.kr/bbs/list.do?sCode=user&mId=311&mPid=121&pageIndex=1&bbsSeqNo=100", "과학기술·정보통신 분야의 지원사업 공고를 수집합니다."),
    ("기후에너지환경부", "공지·공고", "https://mcee.go.kr/home/web/board/list.do?menuId=10524&boardMasterId=39", "기후·에너지·환경 분야의 공지 및 공고를 수집합니다."),
    ("고용노동부", "국고보조사업", "https://www.moel.go.kr/info/govsupport/govsupportcon/govSupportSubList.do", "고용·노동 분야의 국고보조사업 정보를 수집합니다."),
    ("국토교통부", "공지사항", "https://www.molit.go.kr/USR/BORD0201/m_69/LST.jsp?id=N01_B", "국토·교통 분야의 주요 공지사항을 수집합니다."),
    ("한국산업기술진흥원", "사업공고", "https://www.kiat.or.kr/front/board/boardContentsListPage.do?MenuId=b159c9dac684471b87256f1e25404f5e&board_id=90", "한국산업기술진흥원의 산업기술 지원사업과 사업공고를 수집합니다."),
]


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sources = [dict(sourceId=i, organizationName=name, boardName=board, listUrl=url,
                    description=description, urlIncludePattern="/detail", detailFetchCount=7 if i == 6 else 2,
                    enabled=i != 6, createdAt="2026-09-30T09:00:00", updatedAt="2026-09-30T09:00:00")
               for i, (name, board, url, description) in enumerate(BOARDS, 1)]
    mutations, unexpected = [], []
    fail_next = hold_patch = hold_read = False
    patch_started, patch_release = asyncio.Event(), asyncio.Event()
    read_started, read_release = asyncio.Event(), asyncio.Event()
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel=os.environ.get("BROWSER_CHANNEL", "msedge"))
        page = await browser.new_page(viewport={"width": 1440, "height": 1000})
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))

        async def mock_api(route):
            nonlocal fail_next
            path, method = urlparse(route.request.url).path, route.request.method
            status = 200
            if method == "PATCH" and path == "/api/monitoring-sources/settings":
                payload = route.request.post_data_json
                assert set(payload) == {"sources"}
                for setting in payload["sources"]:
                    assert set(setting) == {"sourceId", "detailFetchCount", "enabled"}
                mutations.append(payload)
                if hold_patch:
                    patch_started.set()
                    await patch_release.wait()
                if fail_next:
                    fail_next, status, data = False, 500, None
                else:
                    data = []
                    for setting in payload["sources"]:
                        source = sources[setting["sourceId"] - 1]
                        source.update(setting)
                        data.append(dict(source))
            elif method == "GET" and path == "/api/monitoring-sources":
                data = [dict(source) for source in sources]
                if hold_read:
                    read_started.set()
                    await read_release.wait()
            elif method == "GET" and path == "/api/monitoring-runs/active":
                data = dict(running=False, runId=None, status=None)
            elif method == "GET" and path == "/api/monitoring-runs":
                data = dict(content=[], totalPages=0, totalElements=0, page=0, size=8, first=True, last=True)
            elif method == "GET" and path == "/api/monitoring-schedule":
                data = dict(enabled=False, frequency="DAILY", executionTime="09:00", customDays=[])
            else:
                unexpected.append((method, path))
                await route.abort()
                return
            await route.fulfill(status=status, content_type="application/json", body=json.dumps(
                dict(isSuccess=status == 200, data=data, message="설정 저장 실패 테스트"), ensure_ascii=False))

        await page.route("https://**/*", lambda route: route.abort())
        await page.route("**/api/**", mock_api)
        await page.add_init_script("sessionStorage.setItem('govinsight.accessToken', 'browser-test-placeholder')")
        await page.goto(BASE + "/monitoring")
        panel = page.get_by_role("region", name="모니터링 소스", exact=True)
        save = panel.get_by_role("button", name="변경사항 저장", exact=True)
        row = panel.get_by_role("article", name="산업통상부", exact=True)
        count = row.get_by_role("spinbutton")
        second = panel.get_by_role("article", name="과학기술정보통신부", exact=True)
        kiat = panel.get_by_role("article", name="한국산업기술진흥원", exact=True)
        await expect(panel.locator("article")).to_have_count(6)
        await expect(save).to_have_count(1)
        await expect(panel.get_by_text("모든 변경사항 저장됨", exact=True)).to_have_count(0)
        await expect(panel.get_by_role("status")).to_have_count(0)
        await expect(panel.get_by_role("button", name="산업통상부 수집 건수 줄이기")).to_have_count(0)
        await expect(save).to_be_disabled()
        await expect(page.get_by_role("button", name="소스 등록")).to_have_count(0)
        assert await panel.locator("article button[type=submit]").count() == 0
        assert await panel.locator('input[type="url"]').count() == 0
        for name, board, url, _ in BOARDS:
            await expect(panel.get_by_role("article", name=name, exact=True).get_by_role("link")).to_have_attribute("href", url)
        await expect(kiat.get_by_role("spinbutton")).to_have_value("7")
        await expect(kiat.get_by_role("switch")).to_have_attribute("aria-checked", "false")

        # Cards keep each institution and its controls close, with readable type at every width.
        for width in (1600, 1440, 1024, 768, 390):
            await page.set_viewport_size({"width": width, "height": 1000})
            await panel.evaluate("el => el.scrollIntoView({block:'start'})")
            assert await panel.evaluate("el => el.scrollWidth <= el.clientWidth"), width
            bounds = await panel.bounding_box()
            assert bounds["height"] < (570 if width >= 1440 else 780), (width, bounds)
            first_card = panel.locator("article").nth(0)
            second_card = panel.locator("article").nth(1)
            first_box, second_box = await first_card.bounding_box(), await second_card.bounding_box()
            if width >= 1440:
                assert abs(first_box["y"] - second_box["y"]) < 2
                assert second_box["x"] > first_box["x"]
            assert await first_card.locator("h3").evaluate("el => parseFloat(getComputedStyle(el).fontSize)") >= 14
            assert await first_card.locator("input").evaluate("el => parseFloat(getComputedStyle(el).fontSize)") >= 15
            icon_box = await first_card.locator(".source-logo").bounding_box()
            name_box = await first_card.locator("h3").bounding_box()
            assert abs((icon_box["y"] + icon_box["height"] / 2) - (name_box["y"] + name_box["height"] / 2)) < 2
            link_box = await first_card.get_by_role("link").bounding_box()
            input_box = await first_card.get_by_role("spinbutton").bounding_box()
            assert link_box["y"] > name_box["y"] + name_box["height"]
            assert link_box["x"] > input_box["x"] + input_box["width"]
            distance = await first_card.locator("input").evaluate("el => { const a = el.getBoundingClientRect(); const b = el.closest('article').querySelector('h3').getBoundingClientRect(); return Math.abs(a.x - b.x); }")
            assert distance < 280, (width, bounds, distance)
            for item in await panel.locator("article").all():
                assert await item.evaluate("el => el.scrollWidth <= el.clientWidth"), width
            await panel.screenshot(path=str(OUT / f"sources-{width}.png"))
        # The sole save button stays visible while scrolling within the list.
        await panel.evaluate("el => window.scrollTo(0, window.scrollY + el.getBoundingClientRect().top + 100)")
        sticky_box = await save.bounding_box()
        assert sticky_box["y"] >= 0, sticky_box
        await save.scroll_into_view_if_needed()
        await count.fill("1")
        await count.press("ArrowUp")
        await expect(count).to_have_value("2")
        await expect(save).to_be_disabled()
        for invalid in ("", "0", "-1", "1.5"):
            await count.fill(invalid)
            await expect(save).to_be_disabled()
        await count.fill("5")
        await second.get_by_role("spinbutton").fill("4")
        await row.get_by_role("switch").click()
        await expect(panel.get_by_role("status")).to_have_text("2개 기관 변경")
        await expect(page.get_by_role("button", name="지금 모니터링 실행")).to_be_disabled()
        assert not mutations, "Edits and toggles must not save until the top button is clicked"
        assert sources[0]["enabled"] is True and sources[0]["detailFetchCount"] == 2
        await page.get_by_role("button", name="새로고침", exact=True).click()
        await expect(count).to_have_value("5")
        await expect(row.get_by_role("switch")).to_have_attribute("aria-checked", "false")
        await expect(save).to_be_enabled()

        fail_next = True
        await save.click()
        await expect(panel.get_by_role("alert")).to_have_text("설정 저장 실패 테스트")
        assert sources[0]["detailFetchCount"] == 2 and sources[1]["detailFetchCount"] == 2
        await expect(count).to_have_value("5")
        await expect(row.get_by_role("switch")).to_have_attribute("aria-checked", "false")
        await save.click()
        await expect(save).to_be_disabled()
        await expect(panel.get_by_role("status")).to_have_count(0)
        assert len(mutations[-1]["sources"]) == 2
        assert sources[0]["detailFetchCount"] == 5 and sources[1]["detailFetchCount"] == 4
        assert sources[0]["enabled"] is False

        hold_patch = hold_read = True
        before = len(mutations)
        await count.fill("9")
        # Start an old read before saving; polls intentionally skip sources during PATCH.
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await asyncio.wait_for(read_started.wait(), 5)
        hold_read = False  # The post-save refresh may return the new committed value.
        await save.evaluate("el => { el.click(); el.click(); }")
        await asyncio.wait_for(patch_started.wait(), 5)
        await expect(count).to_be_disabled()
        await expect(second.get_by_role("switch")).to_be_disabled()
        assert len(mutations) == before + 1
        patch_release.set()
        await expect(panel.get_by_role("status")).to_have_count(0)
        await expect(count).to_be_enabled()
        await expect(count).to_have_value("9")
        read_release.set()
        await page.wait_for_timeout(150)
        await expect(count).to_have_value("9")
        hold_patch = False
        await page.reload()
        await expect(count).to_have_value("9")
        await expect(row.get_by_role("switch")).to_have_attribute("aria-checked", "false")
        await expect(kiat.get_by_role("spinbutton")).to_have_value("7")
        for source in sources:
            source["enabled"] = False
        await page.reload()
        await expect(panel.locator("article")).to_have_count(6)
        await expect(page.get_by_role("button", name="지금 모니터링 실행")).to_be_disabled()
        assert not unexpected, unexpected
        assert not errors, errors
        await browser.close()
    print("Readable grouped cards (two columns on desktop), aligned names, footer links, direct numeric input, single batch save, drafts, validation, failures/retry, duplicate click, stale reads, reload and 1600/1440/1024/768/390px layout passed.")


asyncio.run(main())
