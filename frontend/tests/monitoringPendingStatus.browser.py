"""Verify pending guidance uses the latest complete API state, without external effects."""
import asyncio
import json
import os
from pathlib import Path
from urllib.parse import urlparse

from playwright.async_api import async_playwright, expect

BASE = os.environ.get("PENDING_PREVIEW_URL", "http://127.0.0.1:4197")
OUT = Path("frontend/dist/pending-status-checks")
ACTIVITY = "/api/monitoring-runs/active"
SCHEDULE = "/api/monitoring-schedule"
SOURCES = "/api/monitoring-sources"
IDLE = "예약 모니터링의 자동 시작을 기다리고 있습니다."
RUNNING = "앞선 모니터링이 끝나면 자동으로 시작합니다."
NO_SOURCES = "활성 소스가 없어 기다리고 있습니다."
RUNNING_NO_SOURCES = "진행 중인 모니터링이 끝나고 활성 소스가 설정되면 시작합니다."
CHECKING = "예약 실행 상태를 확인하고 있습니다."
UNAVAILABLE = "예약 실행 상태를 확인하지 못했습니다. 잠시 후 다시 확인합니다."


class Gate:
    def __init__(self):
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.finished = asyncio.Event()


async def main():
    schedule = dict(enabled=True, frequency="DAILY", executionTime="09:00", customDays=[], pendingScheduledAt="2026-10-08T14:00:00")
    activity = dict(running=False, runId=None, status=None)
    active_source = True
    next_gate, failures = {}, set()
    errors, unexpected = [], []
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel=os.environ.get("BROWSER_CHANNEL", "msedge"))
        page = await browser.new_page(viewport={"width": 1440, "height": 1000})
        page.on("pageerror", lambda error: errors.append(str(error)))
        await page.add_init_script("sessionStorage.setItem('govinsight.accessToken','browser-test-placeholder')")

        async def mock_api(route):
            path, method = urlparse(route.request.url).path, route.request.method
            if method != "GET":
                unexpected.append((method, path))
                await route.abort()
                return
            if path == ACTIVITY:
                data = dict(activity)
            elif path == SCHEDULE:
                data = dict(schedule)
            elif path == SOURCES:
                data = [dict(sourceId=1, organizationName="기관", boardName="게시판", listUrl="https://example.org", enabled=active_source, detailFetchCount=2, description="", urlIncludePattern="/view", createdAt="2026-10-08T09:00:00", updatedAt="2026-10-08T09:00:00")]
            elif path == "/api/monitoring-runs":
                data = dict(content=[], totalPages=0, totalElements=0, page=0, size=8, first=True, last=True)
            else:
                unexpected.append((method, path))
                await route.abort()
                return
            status = 500 if path in failures else 200
            gate = next_gate.pop(path, None)
            if gate:
                gate.started.set()
                await gate.release.wait()
            await route.fulfill(status=status, content_type="application/json", body=json.dumps(dict(isSuccess=status == 200, data=data if status == 200 else None, message="조회 실패 검증"), ensure_ascii=False))
            if gate:
                gate.finished.set()

        await page.route("https://**/*", lambda route: route.abort())
        await page.route("**/api/**", mock_api)
        initial_sources = Gate()
        next_gate[SOURCES] = initial_sources
        await page.goto(BASE + "/monitoring")
        await asyncio.wait_for(initial_sources.started.wait(), 5)
        source_panel = page.get_by_role("region", name="모니터링 소스", exact=True)
        await expect(source_panel.get_by_text("불러오는 중입니다", exact=True)).to_be_visible()
        await expect(source_panel.get_by_text("기본 소스를 불러오지 못했어요", exact=True)).to_have_count(0)
        initial_sources.release.set()
        await asyncio.wait_for(initial_sources.finished.wait(), 5)
        pending = page.locator(".schedule-pending")
        description = pending.locator("p")
        run = page.locator(".button--run")
        await expect(description).to_have_text(IDLE)
        await expect(run).to_be_disabled()
        await expect(pending).not_to_contain_text(RUNNING)
        OUT.mkdir(parents=True, exist_ok=True)

        async def refresh(expected):
            async with page.expect_response(lambda response: urlparse(response.url).path == ACTIVITY), page.expect_response(lambda response: urlparse(response.url).path == SCHEDULE), page.expect_response(lambda response: urlparse(response.url).path == SOURCES):
                await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
            await expect(description).to_have_text(expected)

        async def screenshot(label):
            for width in (1440, 390):
                await page.set_viewport_size({"width": width, "height": 1000})
                await pending.scroll_into_view_if_needed()
                assert await pending.evaluate("el => el.scrollWidth <= el.clientWidth"), (label, width)
                assert await page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), (label, width)
                await pending.screenshot(path=str(OUT / f"{label}-{width}.png"))

        await screenshot("idle")
        activity.update(running=True, runId=4, status="COLLECTED")
        await refresh(RUNNING)
        await screenshot("running")

        # Poll source configuration, without a reload or changing an unsaved draft.
        source_switch = page.get_by_role("article", name="기관", exact=True).get_by_role("switch")
        await source_switch.click()
        await expect(source_switch).to_have_attribute("aria-checked", "false")
        await refresh(RUNNING)
        await expect(source_switch).to_have_attribute("aria-checked", "false")
        active_source = False
        await expect(description).to_have_text(RUNNING_NO_SOURCES, timeout=8000)
        await screenshot("running-no-sources")
        activity.update(running=False, runId=None, status=None)
        await refresh(NO_SOURCES)
        await screenshot("no-sources")
        active_source = True
        await refresh(IDLE)

        # A local count draft must not invalidate the server's fresh enabled-source count.
        active_source = False
        gate = Gate()
        next_gate[SOURCES] = gate
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await asyncio.wait_for(gate.started.wait(), 5)
        count = page.get_by_role("article", name="기관", exact=True).get_by_role("spinbutton")
        await count.fill("7")
        gate.release.set()
        await asyncio.wait_for(gate.finished.wait(), 5)
        await expect(description).to_have_text(NO_SOURCES)
        await expect(count).to_have_value("7")
        active_source = True
        await refresh(IDLE)
        await expect(count).to_have_value("7")

        # Each delayed read preserves the pending identity but stops claiming a known cause.
        for path in (ACTIVITY, SCHEDULE, SOURCES):
            gate = Gate()
            next_gate[path] = gate
            await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
            await asyncio.wait_for(gate.started.wait(), 5)
            await expect(description).to_have_text(CHECKING)
            if path == ACTIVITY:
                await screenshot("checking")
            await expect(pending).to_contain_text("예약 대기 · 14:00")
            await expect(run).to_be_disabled()
            gate.release.set()
            await asyncio.wait_for(gate.finished.wait(), 5)
            await expect(description).to_have_text(IDLE)

        # Each failed read is recoverable, retains pending state, and blocks manual starts.
        for path in (ACTIVITY, SCHEDULE, SOURCES):
            failures.add(path)
            await refresh(UNAVAILABLE)
            if path == ACTIVITY:
                await screenshot("unavailable")
            await expect(pending).to_contain_text("예약 대기 · 14:00")
            await expect(run).to_be_disabled()
            failures.remove(path)
            await refresh(IDLE)

        # A read that never finishes becomes unavailable after the bounded API timeout.
        gate = Gate()
        next_gate[ACTIVITY] = gate
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await asyncio.wait_for(gate.started.wait(), 5)
        await expect(description).to_have_text(CHECKING)
        await expect(description).to_have_text(UNAVAILABLE, timeout=13000)
        await expect(pending).to_contain_text("예약 대기 · 14:00")
        await expect(run).to_be_disabled()
        gate.release.set()
        await asyncio.wait_for(gate.finished.wait(), 5)
        await refresh(IDLE)

        # A late old cycle must not overwrite a newer consistent running/no-source state.
        gate = Gate()
        next_gate[ACTIVITY] = gate
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
        await asyncio.wait_for(gate.started.wait(), 5)
        await expect(description).to_have_text(CHECKING)
        activity.update(running=True, runId=5, status="RUNNING")
        active_source = False
        await refresh(RUNNING_NO_SOURCES)
        gate.release.set()
        await asyncio.wait_for(gate.finished.wait(), 5)
        await page.wait_for_timeout(150)
        await expect(description).to_have_text(RUNNING_NO_SOURCES)
        await expect(pending).to_contain_text("예약 대기 · 14:00")
        assert not errors, errors
        assert not unexpected, unexpected
        await browser.close()
        print("PASS: idle/running/source-zero guidance, source polling/draft preservation and in-flight edit, three slow reads, three failed reads/recovery, API timeout, stale cycles, desktop/mobile")


asyncio.run(main())
