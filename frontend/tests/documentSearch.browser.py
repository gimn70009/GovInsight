"""Exercise document search against isolated API fixtures, without a live backend."""
import asyncio
import json
import os
from urllib.parse import parse_qs, urlparse

from playwright.async_api import async_playwright, expect

BASE = os.environ.get("DOCUMENT_SEARCH_PREVIEW_URL", "http://127.0.0.1:4191")


async def main():
    rows = [dict(
        runId=7, detectionId=i + 1, documentId=i + 1, versionId=i + 1,
        organizationName="테스트 기관", boardName="공고", title=f"일반 공고 {i}" if i < 25 else f"스마트워치 AI {i}",
        changeType="NEW_DOCUMENT", attachmentCount=0, importance="NORMAL",
        opportunityScore=80 if i >= 28 else 40, opportunityPriority="HIGH" if i >= 28 else "NORMAL",
        lastCheckedAt="2026-10-02T09:00:00",
    ) for i in range(50)]
    saved_ids = [*range(1, 26), *range(41, 46)]
    requests, unexpected, errors = [], [], []
    slow_started, slow_release = asyncio.Event(), asyncio.Event()

    async with async_playwright() as p:
        browser = await p.chromium.launch(channel=os.environ.get("BROWSER_CHANNEL", "msedge"))
        page = await browser.new_page(viewport={"width": 1440, "height": 1000})
        page.on("pageerror", lambda error: errors.append(str(error)))

        async def mock_api(route):
            url = urlparse(route.request.url)
            params = {key: values[0] for key, values in parse_qs(url.query).items()}
            status = 200
            if url.path == "/api/monitoring-runs":
                data = dict(content=[dict(runId=7, requestedAt="2026-10-02T09:00:00", totalSourceCount=6, detectedDocumentCount=50)], totalPages=1, totalElements=1)
            elif url.path == "/api/bookmarks/versions":
                data = saved_ids
            elif url.path in ("/api/document-detections", "/api/bookmarks/documents"):
                requests.append((url.path, params))
                term = params.get("query", "").lower()
                if term == "느림":
                    slow_started.set()
                    await slow_release.wait()
                candidates = rows
                if url.path == "/api/bookmarks/documents":
                    assert "runId" not in params
                    candidates = [item for item in rows if item["versionId"] in saved_ids]
                candidates = [item for item in candidates if term in f'{item["organizationName"]} {item["boardName"]} {item["title"]}'.lower()]
                if "priority" in params:
                    candidates = [item for item in candidates if item["opportunityPriority"] == params["priority"]]
                if params.get("sort") == "OPPORTUNITY_SCORE":
                    candidates = sorted(candidates, key=lambda item: -item["opportunityScore"])
                index, size = int(params["page"]), int(params["size"])
                total = len(candidates)
                data = dict(content=candidates[index * size:(index + 1) * size], page=index, size=size,
                            totalElements=total, totalPages=(total + size - 1) // size, first=index == 0,
                            last=(index + 1) * size >= total)
                if term == "오류":
                    status, data = 500, None
            else:
                unexpected.append(url.path)
                await route.abort()
                return
            await route.fulfill(status=status, content_type="application/json", body=json.dumps(
                dict(isSuccess=status == 200, data=data, message="검색 실패 테스트"), ensure_ascii=False))

        await page.route("https://**/*", lambda route: route.abort())
        await page.route("**/api/**", mock_api)
        await page.add_init_script("sessionStorage.setItem('govinsight.accessToken', 'browser-test-placeholder')")
        await page.goto(BASE + "/documents")
        search = page.get_by_placeholder("기관, 게시판, 제목 검색")
        items = page.locator(".document-row")
        count = page.locator(".header-stat strong")
        await expect(items).to_have_count(20)
        await expect(count).to_have_text("50건")
        await expect(page.get_by_text("스마트워치 AI 25", exact=True)).to_have_count(0)
        await page.get_by_role("button", name="다음", exact=True).click()
        await expect(page.locator(".pagination b")).to_have_text("2")
        await search.fill("스마트워치")
        await expect(count).to_have_text("25건")
        await expect(page.locator(".pagination b")).to_have_text("1")
        await expect(items).to_have_count(20)
        assert requests[-1][1]["query"] == "스마트워치"
        assert requests[-1][1]["page"] == "0"
        assert requests[-1][1]["runId"] == "7"
        await page.get_by_role("button", name="다음", exact=True).click()
        await expect(items).to_have_count(5)
        await page.get_by_role("button", name="우선 검토", exact=True).click()
        await expect(count).to_have_text("22건")
        await expect(page.locator(".pagination b")).to_have_text("1")
        assert requests[-1][1]["priority"] == "HIGH"
        await page.get_by_role("button", name="다음", exact=True).click()
        await expect(items).to_have_count(2)
        await page.get_by_role("button", name="검토 권장", exact=True).click()
        await expect(items).to_have_count(3)
        await expect(count).to_have_text("3건")
        await page.get_by_role("checkbox", name="기회 점수 높은 순").check()
        await expect(items).to_have_count(3)
        await page.wait_for_function("document.querySelector('.loading') === null")
        # Changing query must preserve the currently selected priority and sort.
        await search.fill("없는 검색어")
        await expect(page.get_by_text("조건에 맞는 게시글이 없어요", exact=True)).to_be_visible()
        await expect(count).to_have_text("0건")
        assert requests[-1][1]["sort"] == "OPPORTUNITY_SCORE"
        assert requests[-1][1]["priority"] == "NORMAL"
        await page.get_by_role("button", name="저장한 게시글").click()
        await expect(count).to_have_text("30건")
        await page.get_by_role("button", name="다음", exact=True).click()
        await expect(items).to_have_count(10)
        await search.fill("스마트워치")
        await expect(items).to_have_count(5)
        await expect(count).to_have_text("5건")
        assert requests[-1][0] == "/api/bookmarks/documents"
        assert requests[-1][1]["page"] == "0"
        assert "runId" not in requests[-1][1]
        await search.fill("느림")
        await asyncio.wait_for(slow_started.wait(), timeout=5)
        await search.fill("스마트워치")
        await expect(items).to_have_count(5)
        slow_release.set()
        await expect(count).to_have_text("5건")
        await search.fill("오류")
        await expect(page.get_by_text("검색 실패 테스트", exact=True)).to_be_visible()
        await search.fill("")
        await expect(count).to_have_text("30건")
        await expect(items).to_have_count(20)
        assert "query" not in requests[-1][1]
        assert not errors, errors
        assert not unexpected, unexpected
        await browser.close()
        print("PASS: cross-page search, priority, totals, page reset, sort, bookmarks, stale responses, error recovery")


asyncio.run(main())
