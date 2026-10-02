"""Verify manual-run guarding in the real UI with isolated API responses."""
import asyncio
import json
import os
from urllib.parse import urlparse
from playwright.async_api import async_playwright, expect

BASE = os.environ.get("RUN_GUARD_PREVIEW_URL", "http://127.0.0.1:4191")


async def main():
    activity = dict(running=True, runId=3, status="COLLECTED")
    posts, errors, unexpected = [], [], []
    hold_read = False
    fail_read = False
    read_started, read_release = asyncio.Event(), asyncio.Event()
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel=os.environ.get("BROWSER_CHANNEL", "msedge"))
        context = await browser.new_context(viewport={"width": 1440, "height": 1000})
        await context.add_init_script("sessionStorage.setItem('govinsight.accessToken','browser-test-placeholder')")

        async def api(route):
            nonlocal hold_read
            path, method = urlparse(route.request.url).path, route.request.method
            status, message = 200, "OK"
            if path == "/api/monitoring-runs/active":
                data = dict(activity)
                if hold_read:
                    hold_read = False
                    read_started.set()
                    await read_release.wait()
                if fail_read:
                    status, data = 500, None
            elif path == "/api/monitoring-runs" and method == "POST":
                posts.append(dict(activity))
                if activity["running"]:
                    status, data, message = 409, None, "이미 모니터링이 진행 중입니다. 완료 후 다시 실행해 주세요."
                else:
                    activity.update(running=True, runId=30, status="ACCEPTED")
                    status, data = 201, dict(runId=30, status="ACCEPTED", triggerType="MANUAL", totalSourceCount=1, requestedAt="2026-10-02T09:00:00")
            elif path == "/api/monitoring-runs":
                # The active run is intentionally absent from this history page.
                data = dict(content=[dict(runId=20, status="COMPLETED", triggerType="MANUAL", requestedAt="2026-10-02T09:00:00",
                                         totalSourceCount=1, detectedDocumentCount=1, warningCount=0)],
                            totalPages=2, totalElements=16, page=0, size=8, first=True, last=False)
            elif path == "/api/monitoring-sources":
                data = [dict(sourceId=1, organizationName="기관", boardName="게시판", listUrl="https://example.org", enabled=True,
                             detailFetchCount=2, description="", urlIncludePattern="/view", createdAt="2026-10-02T09:00:00", updatedAt="2026-10-02T09:00:00")]
            elif path == "/api/monitoring-schedule":
                data = dict(enabled=False, frequency="DAILY", executionTime="09:00", customDays=[])
            else:
                unexpected.append((method, path))
                await route.abort()
                return
            await route.fulfill(status=status, content_type="application/json", body=json.dumps(
                dict(isSuccess=status < 400, data=data, message=message), ensure_ascii=False))

        await context.route("https://**/*", lambda route: route.abort())
        await context.route("**/api/**", api)
        page = await context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        await page.goto(BASE + "/monitoring")
        button = page.locator(".button--run")
        await expect(button).to_have_text("모니터링 진행 중")
        await expect(button).to_be_disabled()
        await expect(page.get_by_text("모니터링이 진행 중입니다. 분석과 보고서 생성이 완료되면 다시 실행할 수 있어요.")).to_be_visible()
        await page.get_by_role("button", name="다음", exact=True).click()
        await expect(page.locator(".pagination b")).to_have_text("2")
        await expect(button).to_be_disabled()
        await page.reload()
        await expect(button).to_have_text("모니터링 진행 중")
        await expect(button).to_be_disabled()
        assert len(posts) == 0

        activity.update(running=False, runId=None, status=None)
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(button).to_be_enabled(timeout=7000)
        other = await context.new_page()
        await other.goto(BASE + "/monitoring")
        other_button = other.locator(".button--run")
        await expect(other_button).to_be_enabled()
        await page.bring_to_front()
        hold_read = True
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await asyncio.wait_for(read_started.wait(), timeout=5)
        await button.click()
        # A second click while React is updating cannot dispatch another start.
        await button.evaluate("el => el.click()")
        await expect(button).to_have_text("모니터링 진행 중")
        assert len(posts) == 1
        read_release.set()
        await expect(button).to_be_disabled()
        # Another tab still shows its earlier idle state; the server rejection is visible.
        await other_button.evaluate("el => el.click()")
        await expect(other.get_by_text("이미 모니터링이 진행 중입니다. 완료 후 다시 실행해 주세요.", exact=True)).to_be_visible()
        await expect(other_button).to_be_disabled()
        assert len(posts) == 2
        assert posts[0]["running"] is False and posts[1]["running"] is True

        await other.close()
        await page.bring_to_front()
        activity.update(running=False, runId=None, status=None)
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(button).to_be_enabled(timeout=7000)
        fail_read = True
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(page.get_by_text("진행 중인 모니터링을 확인하지 못했습니다. 잠시 후 다시 확인합니다.")).to_be_visible()
        await expect(button).to_be_disabled()
        fail_read = False
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(button).to_be_enabled(timeout=7000)
        assert not errors, errors
        assert not unexpected, unexpected
        await browser.close()
        print("PASS: active work outside page, reload, completion unlock, double click, late status, other-tab 409, status failure recovery")


asyncio.run(main())
