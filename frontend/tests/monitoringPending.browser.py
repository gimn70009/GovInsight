"""Exercise pending monitoring UI against isolated API fixtures; no collection occurs."""
import asyncio
import json
import os
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from playwright.async_api import async_playwright, expect

BASE = os.environ.get("PENDING_PREVIEW_URL", "http://127.0.0.1:4197")
OUT = Path("frontend/dist/pending-checks")


async def main():
    schedule = dict(enabled=True, frequency="DAILY", executionTime="09:00", customDays=[], pendingScheduledAt=None)
    activity = dict(running=False, runId=None, status=None)
    deletes, puts, errors, unexpected = [], [], [], []
    hold_read = hold_delete = fail_read = unsupported_schedule = False
    delete_failure = 0
    active_source = True
    read_started, read_release = asyncio.Event(), asyncio.Event()
    delete_started, delete_release = asyncio.Event(), asyncio.Event()
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel=os.environ.get("BROWSER_CHANNEL", "msedge"))
        page = await browser.new_page(viewport={"width": 1440, "height": 1000})
        page.on("pageerror", lambda error: errors.append(str(error)))
        await page.add_init_script("sessionStorage.setItem('govinsight.accessToken','browser-test-placeholder')")

        async def mock_api(route):
            nonlocal hold_read, delete_failure
            parsed, method = urlparse(route.request.url), route.request.method
            path, status, message = parsed.path, 200, "OK"
            if path == "/api/monitoring-schedule" and method == "GET":
                data = dict(schedule)
                if hold_read:
                    hold_read = False
                    read_started.set()
                    await read_release.wait()
                if unsupported_schedule:
                    status, data = 404, None
                elif fail_read:
                    status, data = 500, None
            elif path == "/api/monitoring-schedule" and method == "PUT":
                data = route.request.post_data_json
                assert set(data) == {"enabled", "frequency", "executionTime", "customDays"}, data
                puts.append(data)
                if any(schedule[k] != data[k] for k in data):
                    schedule["pendingScheduledAt"] = None
                schedule.update(data)
                data = dict(schedule)
            elif path == "/api/monitoring-schedule/pending" and method == "DELETE":
                target = parse_qs(parsed.query)["scheduledAt"][0]
                deletes.append(target)
                if hold_delete:
                    delete_started.set()
                    await delete_release.wait()
                if delete_failure:
                    status, data, message = delete_failure, None, "대기 예약이 변경되었습니다. 현재 상태를 확인해 주세요."
                    delete_failure = 0
                elif target != schedule["pendingScheduledAt"]:
                    status, data, message = 409, None, "대기 예약이 변경되었습니다. 현재 상태를 확인해 주세요."
                else:
                    schedule["pendingScheduledAt"] = None
                    data = dict(schedule)
            elif path == "/api/monitoring-runs/active":
                data = dict(activity)
            elif path == "/api/monitoring-runs" and method == "GET":
                data = dict(content=[dict(runId=1, status="COMPLETED", triggerType="MANUAL", requestedAt="2026-10-08T09:00:00", totalSourceCount=1, detectedDocumentCount=1, warningCount=0)], totalPages=2, totalElements=16, page=0, size=8, first=True, last=False)
            elif path == "/api/monitoring-sources":
                data = [dict(sourceId=1, organizationName="기관", boardName="게시판", listUrl="https://example.org", enabled=active_source, detailFetchCount=2, description="", urlIncludePattern="/view", createdAt="2026-10-08T09:00:00", updatedAt="2026-10-08T09:00:00")]
            else:
                unexpected.append((method, path))
                await route.abort()
                return
            await route.fulfill(status=status, content_type="application/json", body=json.dumps(dict(isSuccess=status < 400, data=data, message=message), ensure_ascii=False))

        await page.route("https://**/*", lambda route: route.abort())
        await page.route("**/api/**", mock_api)
        await page.goto(BASE + "/monitoring")
        pending = page.locator(".schedule-pending")
        cancel = page.get_by_role("button", name="대기 취소", exact=True)
        run = page.locator(".button--run")
        time = page.locator('.schedule-time input')
        save = page.get_by_role("button", name="일정 저장", exact=True)
        toggle = page.get_by_role("switch", name="자동 모니터링 사용")
        await expect(run).to_be_enabled()
        await expect(pending).to_have_count(0)

        # The existing five-second refresh reveals pending work and preserves edits.
        await time.fill("11:17")
        schedule["pendingScheduledAt"] = "2026-10-08T14:00:00"
        await expect(pending).to_contain_text("예약 대기 · 14:00", timeout=8000)
        await expect(run).to_be_disabled()
        await expect(run).to_have_text("예약 실행 대기 중")
        await expect(time).to_have_value("11:17")
        await page.get_by_role("button", name="다음", exact=True).click()
        await expect(page.locator(".pagination b")).to_have_text("2")
        await expect(time).to_have_value("11:17")
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(time).to_have_value("11:17")

        # One cancellation targets exactly the rendered occurrence, even on double click.
        hold_delete = True
        await cancel.click()
        await asyncio.wait_for(delete_started.wait(), 5)
        await page.locator(".schedule-pending button").evaluate("el => el.click()")
        await expect(page.locator(".schedule-pending button")).to_be_disabled()
        delete_release.set()
        await expect(pending).to_have_count(0)
        await expect(run).to_be_enabled()
        await expect(time).to_have_value("11:17")
        assert deletes == ["2026-10-08T14:00:00"], deletes
        hold_delete = False

        # An older pending read cannot resurrect a cancelled occurrence.
        schedule["pendingScheduledAt"] = "2026-10-08T14:01:00"
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(pending).to_contain_text("14:01")
        hold_read = True
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await asyncio.wait_for(read_started.wait(), 5)
        await cancel.click()
        await expect(pending).to_have_count(0)
        read_release.set()
        await page.wait_for_timeout(150)
        await expect(pending).to_have_count(0)
        await expect(time).to_have_value("11:17")

        # A save invalidates old polls and carries only editable schedule fields.
        schedule["pendingScheduledAt"] = "2026-10-08T14:02:00"
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(pending).to_contain_text("14:02")
        read_started.clear()
        read_release.clear()
        hold_read = True
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await asyncio.wait_for(read_started.wait(), 5)
        await save.click()
        await expect(pending).to_have_count(0)
        read_release.set()
        await page.wait_for_timeout(150)
        await expect(pending).to_have_count(0)
        await expect(time).to_have_value("11:17")
        assert puts[-1]["executionTime"] == "11:17"

        # The enabled toggle uses saved settings and preserves the unsaved draft.
        await time.fill("12:34")
        schedule["pendingScheduledAt"] = "2026-10-08T14:03:00"
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(pending).to_contain_text("14:03")
        await toggle.click()
        await expect(toggle).to_have_attribute("aria-checked", "false")
        await expect(pending).to_have_count(0)
        await expect(time).to_have_value("12:34")
        assert puts[-1]["executionTime"] == "11:17"

        # If the occurrence has already started, 409 must not look like success.
        schedule.update(enabled=True, pendingScheduledAt="2026-10-08T14:04:00")
        await page.reload()
        await expect(pending).to_contain_text("14:04")
        schedule["pendingScheduledAt"] = None
        activity.update(running=True, runId=4, status="ACCEPTED")
        await cancel.click()
        await expect(page.get_by_role("alert")).to_contain_text("대기 예약이 변경되었습니다")
        await expect(pending).to_have_count(0)
        await expect(run).to_have_text("모니터링 진행 중")
        await expect(page.locator(".toast")).to_have_count(0)

        # Another pending occurrence is refreshed but never cancelled by the old button.
        activity.update(running=False, runId=None, status=None)
        schedule["pendingScheduledAt"] = "2026-10-08T14:05:00"
        await page.reload()
        await expect(pending).to_contain_text("14:05")
        schedule["pendingScheduledAt"] = "2026-10-08T15:00:00"
        await cancel.click()
        await expect(pending).to_contain_text("15:00")
        await expect(page.get_by_role("alert")).to_contain_text("대기 예약이 변경되었습니다")
        assert deletes[-1] == "2026-10-08T14:05:00"
        await expect(run).to_be_disabled()

        # A failed cancellation is retryable, and a failed status read never unlocks manual runs.
        delete_failure = 500
        await cancel.click()
        await expect(pending).to_contain_text("15:00")
        await expect(cancel).to_be_enabled()
        fail_read = True
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(page.get_by_role("alert")).to_contain_text("예약 대기 상태를 확인하지 못했습니다")
        await expect(run).to_be_disabled()
        fail_read = False
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(run).to_have_text("예약 실행 대기 중")

        # Disabled scheduling has no controller: only GET 404 permits manual runs.
        unsupported_schedule = True
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(page.get_by_role("alert")).to_contain_text("자동 모니터링 기능이 비활성화되어 있습니다.")
        await expect(run).to_be_enabled()
        await expect(pending).to_have_count(0)
        await expect(toggle).to_be_disabled()
        await expect(toggle).to_have_attribute("aria-checked", "false")
        await expect(time).to_be_disabled()
        await expect(save).to_be_disabled()
        unsupported_schedule = False
        fail_read = True
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(page.get_by_role("alert")).to_contain_text("예약 대기 상태를 확인하지 못했습니다")
        await expect(run).to_be_disabled()
        fail_read = False
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await expect(pending).to_contain_text("15:00")
        await expect(toggle).to_be_enabled()
        await expect(run).to_be_disabled()

        # A pending reservation with no active sources remains visible and fits mobile.
        active_source = False
        await page.reload()
        await expect(pending).to_contain_text("활성 소스가 없어 기다리고 있습니다.")
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 390):
            await page.set_viewport_size({"width": width, "height": 1000})
            await pending.scroll_into_view_if_needed()
            assert await pending.evaluate("el => el.scrollWidth <= el.clientWidth"), width
            assert await page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), width
            await pending.screenshot(path=str(OUT / f"pending-{width}.png"))
        assert not errors, errors
        assert not unexpected, unexpected
        await browser.close()
        print("PASS: pending polling, draft preservation, manual guard, exact cancellation, double click, stale polls, save/toggle, 409 races, failure recovery, disabled scheduling 404 vs 5xx, no active sources, desktop/mobile")


asyncio.run(main())
