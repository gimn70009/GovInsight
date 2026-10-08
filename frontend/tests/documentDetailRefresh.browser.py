"""Verify document detail polling using only mocked GET APIs and an Edge virtual clock."""
import asyncio
import copy
import os
from collections import Counter, defaultdict, deque
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse

from playwright.async_api import Error, async_playwright, expect

BASE = os.environ.get("DOCUMENT_DETAIL_PREVIEW_URL", "http://127.0.0.1:4197")
OUT = Path("frontend/dist/document-detail-refresh-checks")
WAITING = "AI 분석을 준비하고 있어요"
EMPTY = "AI 분석 결과가 없습니다"
EMPTY_REASON = "분석 처리가 끝났지만 저장된 결과가 없습니다. 원문을 확인하거나 다시 모니터링해 주세요."
PROPOSAL_ENDED = "사업 제안 준비가 종료되었지만 결과를 저장하지 못했습니다. 원문을 확인하거나 다시 모니터링해 주세요."
ERROR_MESSAGE = "상세 조회 실패 검증"
SUMMARY = "새로 도착한 분석 결과입니다. 제조 AI 실증 과업을 검토합니다."
READY_TEXT = "자동 갱신으로 도착한 제안 본문입니다. 실증 범위를 협의합니다."


def row(identifier):
    return dict(runId=7, detectionId=identifier, documentId=identifier, versionId=identifier,
                organizationName="검증 기관", boardName="사업공고", title=f"상세 갱신 공고 {identifier}",
                changeType="NEW_DOCUMENT", attachmentCount=0, importance="NORMAL",
                opportunityScore=70, opportunityPriority="NORMAL", lastCheckedAt="2026-10-08T09:00:00")


def analysis(status="GENERATING", summary=SUMMARY):
    return dict(summary=summary, keyPoints=["실증 과업과 신청 조건을 확인합니다."], importance="NORMAL",
                reason="제조 AI 실증 관련 공고입니다.", eligibility="REVIEW_REQUIRED",
                favorableOrNot="NOT_APPLICABLE", opportunity=None, applicationDeadline=None,
                proposal=dict(documentType="PROPOSAL_REQUEST", draftStatus=status,
                    draftReason="제안을 준비하고 있습니다.", sourceAttachmentNames=[], templateSections=[],
                    preparation=None, preparationSchemaVersion=19,
                    sections=[dict(title="우리 회사와 연결되는 부분", body="제조 AI 역량을 검토합니다."),
                              dict(title="이 공고에서 중요하게 볼 점", body="필수 참여 조건을 확인합니다.")],
                    draftSections=[dict(title="수행 계획", body=READY_TEXT)] if status == "READY" else []))


def detail(identifier=1, pending=True, result=None):
    return dict(**row(identifier), originalUrl=f"https://example.org/notice/{identifier}",
                publishedAt="2026-10-08T09:00:00", analysisPending=pending,
                analysis=result, attachments=[])


def paged(items):
    return dict(content=items, totalPages=1, totalElements=len(items), page=0, size=20, first=True, last=True)


@dataclass
class Gate:
    started: asyncio.Event = field(default_factory=asyncio.Event)
    release: asyncio.Event = field(default_factory=asyncio.Event)
    finished: asyncio.Event = field(default_factory=asyncio.Event)


@dataclass
class Reply:
    data: dict | None = None
    status: int = 200
    gate: Gate | None = None


class Harness:
    def __init__(self, browser):
        self.browser = browser
        self.page = None
        self.states = {1: detail(), 2: detail(2, False, analysis("READY", "두 번째 공고의 분석입니다."))}
        self.replies = defaultdict(deque)
        self.counts = Counter()
        self.started = defaultdict(asyncio.Event)
        self.finished = defaultdict(asyncio.Event)
        self.gates = []
        self.errors = []
        self.unexpected = []

    async def start(self):
        self.page = await self.browser.new_page(viewport={"width": 1440, "height": 1000})
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        await self.page.add_init_script("""
          sessionStorage.setItem('govinsight.accessToken', 'browser-test-placeholder');
          window.__detailRequests = [];
          const originalFetch = window.fetch;
          window.fetch = (input, options) => {
            const url = typeof input === 'string' ? input : input.url;
            if (!/\\/api\\/document-detections\\/\\d+$/.test(url)) return originalFetch(input, options);
            const record = {url, active: true, aborted: false};
            window.__detailRequests.push(record);
            options?.signal?.addEventListener('abort', () => { record.aborted = true; });
            return originalFetch(input, options).then(response => {
              record.active = false; return response;
            }, error => { record.active = false; record.error = error.name; throw error; });
          };
        """)
        await self.page.clock.install(time="2026-10-08T00:00:00Z")
        await self.page.route("https://**/*", lambda route: route.abort())
        await self.page.route("**/api/**", self.route)
        await self.page.goto(BASE + "/documents")
        await expect(self.page.locator(".document-row")).to_have_count(2)
        await self.page.clock.pause_at("2026-10-08T00:01:00Z")
        return self

    async def route(self, route):
        path = urlparse(route.request.url).path
        if route.request.method != "GET":
            self.unexpected.append((route.request.method, path))
            await route.abort()
            return
        gate, identifier, count = None, None, None
        if path == "/api/monitoring-runs":
            data = paged([dict(runId=7, requestedAt="2026-10-08T09:00:00", status="COLLECTED",
                               triggerType="MANUAL", totalSourceCount=1, detectedDocumentCount=2, warningCount=0)])
        elif path == "/api/bookmarks/versions":
            data = []
        elif path == "/api/document-detections":
            data = paged([row(1), row(2)])
        elif path in ("/api/document-detections/1", "/api/document-detections/2"):
            identifier = int(path.rsplit("/", 1)[1])
            self.counts[identifier] += 1
            count = self.counts[identifier]
            reply = self.replies[identifier].popleft() if self.replies[identifier] else Reply(self.states[identifier])
            data, gate = copy.deepcopy(reply.data), reply.gate
            self.started[identifier, count].set()
            if gate:
                self.gates.append(gate)
                gate.started.set()
                await gate.release.wait()
            try:
                await route.fulfill(status=reply.status, json=dict(isSuccess=reply.status == 200,
                                    data=data, message=ERROR_MESSAGE if reply.status != 200 else "OK"))
            except Error:
                if gate is None:
                    raise
            finally:
                self.finished[identifier, count].set()
                if gate:
                    gate.finished.set()
            return
        elif path.endswith("/similar-notices"):
            data = dict(currentNotice={}, similarNotices=[])
        elif path.endswith("/proposal-sources") or path.endswith("/proposal-drafts"):
            data = []
        elif path.endswith("/proposal-drafts/state"):
            data = dict(drafts=[], running=[])
        else:
            self.unexpected.append((route.request.method, path))
            await route.abort()
            return
        await route.fulfill(json=dict(isSuccess=True, data=data, message="OK"))

    async def open(self, identifier=1):
        await self.page.locator(".document-row").filter(has_text=f"상세 갱신 공고 {identifier}").click()
        await self.wait_request(identifier, 1)

    async def wait_request(self, identifier, count):
        await asyncio.wait_for(self.started[identifier, count].wait(), 5)

    async def tick(self, identifier=1):
        expected = self.counts[identifier] + 1
        await self.page.clock.run_for(3001)
        await self.wait_request(identifier, expected)

    async def stopped(self, identifier=1):
        before = self.counts[identifier]
        await self.page.clock.run_for(12001)
        assert self.counts[identifier] == before, (before, self.counts)

    async def close(self):
        for gate in self.gates:
            gate.release.set()
        await self.page.close()
        assert not self.errors, self.errors
        assert not self.unexpected, self.unexpected


async def waiting_to_ready(browser):
    h = await Harness(browser).start()
    try:
        await h.open()
        await expect(h.page.get_by_text(WAITING, exact=True)).to_be_visible()
        h.states[1] = detail(result=analysis())
        await h.tick()
        await expect(h.page.locator(".summary-text")).to_have_text(SUMMARY)
        await h.page.get_by_role("button", name="사업 제안", exact=True).click()
        await expect(h.page.locator(".proposal-draft-empty")).to_contain_text("별도로 준비")
        h.states[1] = detail(pending=False, result=analysis("READY"))
        await h.tick()
        await expect(h.page.locator(".proposal-draft-panel")).to_contain_text(READY_TEXT)
        await expect(h.page.locator(".detail-tabs button.active")).to_have_text("사업 제안")
        await h.stopped()
        for width in (1440, 390):
            await h.page.set_viewport_size({"width": width, "height": 1000})
            assert await h.page.locator(".drawer--detail").evaluate("el => el.scrollWidth <= el.clientWidth")
            OUT.mkdir(parents=True, exist_ok=True)
            await h.page.screenshot(path=str(OUT / f"ready-{width}.png"))
    finally:
        await h.close()


async def terminal_without_result(browser, had_analysis):
    h = await Harness(browser).start()
    try:
        if had_analysis:
            h.states[1] = detail(result=analysis())
        await h.open()
        if had_analysis:
            await expect(h.page.locator(".summary-text")).to_have_text(SUMMARY)
            await h.page.get_by_role("button", name="사업 제안", exact=True).click()
        else:
            await expect(h.page.get_by_text(WAITING, exact=True)).to_be_visible()
        h.states[1]["analysisPending"] = False
        await h.tick()
        if had_analysis:
            await expect(h.page.locator(".proposal-draft-panel")).to_contain_text(PROPOSAL_ENDED)
        else:
            await expect(h.page.get_by_text(EMPTY, exact=True)).to_be_visible()
            await expect(h.page.get_by_text(EMPTY_REASON, exact=True)).to_be_visible()
            await expect(h.page.get_by_text(WAITING, exact=True)).to_have_count(0)
        await h.stopped()
    finally:
        await h.close()


async def initial_terminal(browser):
    h = await Harness(browser).start()
    try:
        h.states[1] = detail(pending=False)
        await h.open()
        await expect(h.page.get_by_text(EMPTY, exact=True)).to_be_visible()
        await h.stopped()
    finally:
        await h.close()


async def retry_errors(browser):
    h = await Harness(browser).start()
    try:
        h.replies[1].append(Reply(status=500))
        await h.open()
        await expect(h.page.get_by_text(ERROR_MESSAGE, exact=True)).to_be_visible()
        await expect(h.page.get_by_role("button", name="다시 불러오기", exact=True)).to_be_visible()
        await h.tick()
        await expect(h.page.get_by_text(WAITING, exact=True)).to_be_visible()
        h.states[1] = detail(result=analysis())
        await h.tick()
        await expect(h.page.locator(".summary-text")).to_have_text(SUMMARY)
        h.replies[1].append(Reply(status=503))
        await h.tick()
        await expect(h.page.get_by_text(ERROR_MESSAGE, exact=True)).to_be_visible()
        await expect(h.page.locator(".summary-text")).to_have_text(SUMMARY)
        h.states[1] = detail(pending=False, result=analysis("READY", "오류 후 복구된 분석입니다."))
        await h.tick()
        await expect(h.page.locator(".summary-text")).to_have_text("오류 후 복구된 분석입니다.")
        await expect(h.page.get_by_text(ERROR_MESSAGE, exact=True)).to_have_count(0)
        await h.stopped()
    finally:
        await h.close()


async def client_error_requires_manual_retry(browser):
    h = await Harness(browser).start()
    try:
        h.replies[1].append(Reply(status=404))
        await h.open()
        await expect(h.page.get_by_text(ERROR_MESSAGE, exact=True)).to_be_visible()
        await h.stopped()
        h.states[1] = detail(pending=False, result=analysis("READY"))
        await h.page.get_by_role("button", name="다시 불러오기", exact=True).click()
        await h.wait_request(1, 2)
        await expect(h.page.locator(".summary-text")).to_have_text(SUMMARY)
        await h.stopped()
    finally:
        await h.close()


async def slow_poll_is_singleflight_and_times_out(browser):
    h = await Harness(browser).start()
    try:
        await h.open()
        await expect(h.page.get_by_text(WAITING, exact=True)).to_be_visible()
        gate = Gate()
        h.replies[1].append(Reply(detail(result=analysis(summary="시간 초과된 오래된 결과")), gate=gate))
        await h.tick()
        await asyncio.wait_for(gate.started.wait(), 5)
        count = h.counts[1]
        await h.page.clock.run_for(9000)
        assert h.counts[1] == count, "A second request started while the current request was in flight"
        await h.page.clock.run_for(1001)
        await expect(h.page.get_by_role("button", name="다시 불러오기", exact=True)).to_be_visible()
        assert await h.page.evaluate("window.__detailRequests.at(-1).aborted")
        h.states[1] = detail(pending=False, result=analysis("READY", "시간 초과 후 최신 결과"))
        gate.release.set()
        await asyncio.wait_for(gate.finished.wait(), 5)
        await h.tick()
        await expect(h.page.locator(".summary-text")).to_have_text("시간 초과 후 최신 결과")
        await expect(h.page.get_by_text("시간 초과된 오래된 결과", exact=True)).to_have_count(0)
        await h.stopped()
    finally:
        await h.close()


async def hidden_tab_pauses_and_resumes(browser):
    h = await Harness(browser).start()
    try:
        await h.open()
        await expect(h.page.get_by_text(WAITING, exact=True)).to_be_visible()
        await h.page.evaluate("""() => {
          Object.defineProperty(document, 'visibilityState', {configurable: true, get: () => 'hidden'});
          Object.defineProperty(document, 'hidden', {configurable: true, get: () => true});
          document.dispatchEvent(new Event('visibilitychange'));
        }""")
        before = h.counts[1]
        await h.page.clock.run_for(12001)
        assert h.counts[1] == before
        h.states[1] = detail(pending=False, result=analysis("READY"))
        await h.page.evaluate("""() => {
          Object.defineProperty(document, 'visibilityState', {configurable: true, get: () => 'visible'});
          Object.defineProperty(document, 'hidden', {configurable: true, get: () => false});
          document.dispatchEvent(new Event('visibilitychange'));
        }""")
        await h.wait_request(1, before + 1)
        await expect(h.page.locator(".summary-text")).to_have_text(SUMMARY)
        await h.stopped()
    finally:
        await h.close()


async def closing_or_switching_ignores_late_response(browser, switch):
    h = await Harness(browser).start()
    try:
        await h.open()
        await expect(h.page.get_by_text(WAITING, exact=True)).to_be_visible()
        gate = Gate()
        h.replies[1].append(Reply(detail(pending=False, result=analysis("READY", "이전 공고의 늦은 결과")), gate=gate))
        await h.tick()
        await asyncio.wait_for(gate.started.wait(), 5)
        if switch:
            # Exercise the existing drawer's detectionId change without accessing React internals.
            await h.page.locator(".document-row").filter(has_text="상세 갱신 공고 2").evaluate("el => el.click()")
            await h.wait_request(2, 1)
            await expect(h.page.locator(".document-detail h2")).to_have_text("상세 갱신 공고 2")
            await expect(h.page.locator(".summary-text")).to_have_text("두 번째 공고의 분석입니다.")
        else:
            await h.page.get_by_role("button", name="목록으로", exact=True).click()
            await expect(h.page.locator(".drawer--detail")).to_have_count(0)
        assert await h.page.evaluate("window.__detailRequests.filter(r => r.url.endsWith('/1')).at(-1).aborted")
        gate.release.set()
        await asyncio.wait_for(gate.finished.wait(), 5)
        await h.stopped()
        await expect(h.page.get_by_text("이전 공고의 늦은 결과", exact=True)).to_have_count(0)
        if switch:
            await expect(h.page.locator(".summary-text")).to_have_text("두 번째 공고의 분석입니다.")
            await h.stopped(2)
        else:
            await expect(h.page.locator(".drawer--detail")).to_have_count(0)
    finally:
        await h.close()


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel=os.environ.get("BROWSER_CHANNEL", "msedge"))
        try:
            await waiting_to_ready(browser)
            await terminal_without_result(browser, False)
            await terminal_without_result(browser, True)
            await initial_terminal(browser)
            await retry_errors(browser)
            await client_error_requires_manual_retry(browser)
            await slow_poll_is_singleflight_and_times_out(browser)
            await hidden_tab_pauses_and_resumes(browser)
            await closing_or_switching_ignores_late_response(browser, False)
            await closing_or_switching_ignores_late_response(browser, True)
        finally:
            await browser.close()
    print("PASS: analysis/proposal refresh, terminal stop, error retries, timeout, singleflight, visibility, close/switch isolation")


if __name__ == "__main__":
    asyncio.run(main())
