"""Participation-only async rewriting: mocked APIs, original evidence, responsive display."""
import asyncio
import copy
import importlib.util
import json
import sys
from pathlib import Path

from playwright.async_api import async_playwright, expect

sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("businessSummary.browser.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)
OUT = Path("frontend/dist/participation-rewrite-checks")
RAW = "가. 업력 7년 이상의 매출액 100억원 달성 중소기업이 대상입니다. [참고1]"
TEXT = "스케일업 100억 트랙은 매출액 100억 원을 달성해야 합니다."


def guide(status):
    verified = status == "READY"
    source = dict(origin="NOTICE_BODY", attachmentName=None, sectionTitle="스케일업 100억 트랙", location=None, excerpt=RAW)
    rule = dict(section="ELIGIBILITY" if verified else "EVIDENCE", label="매출액",
                text=TEXT if verified else "제품이 대상입니다.", scope=None, audience=None,
                details=["잘린 표 조각"], exceptions=[], guidance=[], outcomes=[], sources=[source])
    entry = dict(rule=rule, context="원문 연결 문장", relation=None, relatedCriteria=[],
                 displayStatus="VERIFIED" if verified else "EVIDENCE_ONLY")
    return dict(conditions=[], rules=[], variants=[], rewriteStatus=status,
                categories=[dict(code="FINANCIAL", label="재무 요건", rows=[
                    dict(code="REVENUE", label="매출액", status="STATED", entries=[entry] if verified else [entry, copy.deepcopy(entry)])])])


async def check(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["participationGuide"] = guide("PENDING")
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
        await expect(region.get_by_role("table")).to_have_count(8)
        assert await region.locator("tbody tr").count() == 40
        await expect(region.get_by_role("status")).to_be_visible()
        await expect(region.locator(".participation-guide__main")).to_have_count(0)
        await expect(region).not_to_contain_text("제품이 대상입니다")
        button = region.get_by_role("button", name="매출액 근거 보기")
        await expect(button).to_have_count(1)
        await button.focus()
        await button.press("Enter")
        await expect(region.locator("blockquote")).to_have_text(RAW)
        await button.press("Space")
        await expect(region).to_contain_text("조건을 정리하고 있습니다.")
        before = h.counts[1]
        partial = copy.deepcopy(data)
        partial["participationGuide"] = guide("READY")
        partial["participationGuide"]["rewriteStatus"] = "PENDING"
        h.states[1] = partial
        await h.page.clock.run_for(3100)
        await h.wait_request(1, before + 1)
        await expect(region.locator(".participation-guide__main")).to_have_text(TEXT)
        await expect(region.get_by_role("status")).to_contain_text("1개 조건을 정리했습니다")
        before = h.counts[1]
        ready = copy.deepcopy(data)
        ready["participationGuide"] = guide("READY")
        h.states[1] = ready
        await h.page.clock.run_for(3100)
        await h.wait_request(1, before + 1)
        await expect(region.locator(".participation-guide__main")).to_have_text(TEXT)
        await expect(region.get_by_role("status")).to_have_count(0)
        await expect(region).not_to_contain_text("잘린 표 조각")
        await expect(region).not_to_contain_text("원문 연결 문장")
        count = h.counts[1]
        await h.page.clock.run_for(10000)
        assert h.counts[1] == count, "terminal rewrite must stop polling"
        # Other document sections remain present with unchanged content.
        await expect(h.page.get_by_text(summary.PURPOSE, exact=True)).to_be_visible()
        await expect(h.page.get_by_text("검증 사업운영원", exact=True)).to_be_visible()
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await region.locator('[data-criterion="REVENUE"]').scroll_into_view_if_needed()
            await expect(region.locator(".participation-guide__main")).to_have_text(TEXT)
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1")
            await h.page.screenshot(path=str(OUT / f"verified-{width}.png"))
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()
    for status in ("UNAVAILABLE", None):
        h = await summary.refresh.Harness(browser).start()
        try:
            data = summary.ready(); data["participationGuide"] = guide(status)
            h.states[1] = data
            await h.open(analysis_tab=False)
            region = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
            await expect(region.locator(".participation-guide__main")).to_have_count(0)
            await region.get_by_role("button", name="매출액 근거 보기").click()
            await expect(region.locator("blockquote")).to_have_text(RAW)
            await expect(region.get_by_role("status")).to_have_count(0)
        finally:
            await h.close()


async def check_actual(browser, path):
    actual = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    expected = [e["rule"]["text"] for c in actual["categories"] for r in c["rows"] for e in r["entries"] if e["displayStatus"] == "VERIFIED"]
    assert expected, "actual model response must contain verified conditions"
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready(); data["participationGuide"] = actual
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
        await expect(region.locator(".participation-guide__main")).to_have_text(expected)
        assert len(set(expected)) == len(expected)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1")
            await region.locator('[data-criterion="MATCHING"]').scroll_into_view_if_needed()
            await h.page.screenshot(path=str(OUT / f"actual-{width}.png"))
        await expect(h.page.get_by_text(summary.PURPOSE, exact=True)).to_be_visible()
        await expect(h.page.get_by_text("검증 사업운영원", exact=True)).to_be_visible()
        assert not h.errors and not h.unexpected
    finally:
        await h.close()


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel="msedge", headless=True)
        try:
            await check(browser)
            if len(sys.argv) > 1:
                await check_actual(browser, sys.argv[1])
        finally:
            await browser.close()
    print("PASS participation rewrite polling, evidence-only, unchanged other sections, 4 widths")


asyncio.run(main())
