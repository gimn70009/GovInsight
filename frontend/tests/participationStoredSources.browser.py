"""Replay stored, source-only eligibility guides against mocked GET APIs."""
import argparse
import asyncio
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


async def check(browser, sample, original):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["title"] = sample["title"]
        data["originalUrl"] = original["url"]
        data["participationGuide"] = sample["guide"]
        data["attachments"] = [
            dict(fileName=item["name"], fileExtension=Path(item["name"]).suffix.lstrip("."),
                 fileSize=0, parseStatus=item["status"], downloadUrl=item["url"])
            for item in original["attachments"]
        ]
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        await expect(region.get_by_role("table")).to_have_count(0)
        await expect(region.locator(".participation-guide__main")).to_have_count(0)
        await expect(region).to_contain_text("정리된 신청 조건을 찾지 못했습니다.")
        await expect(region.get_by_role("status")).to_have_count(0)
        await expect(region.locator("blockquote")).to_have_count(0)
        await expect(region.locator(".participation-guide__unresolved")).to_have_count(0)
        await expect(region.get_by_role("button")).to_have_count(0)
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            await region.scroll_into_view_if_needed()
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), (sample["id"], width)
            await h.page.screenshot(path=str(OUT / f"actual-{sample['id']}-{width}-empty.png"))
        await h.stopped()
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
        return dict(id=sample["id"], conditionRows=0, standaloneEvidenceCards=0,
                    viewports=[1440, 390, 320], paidApiCalls=0)
    finally:
        await h.close()


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--replay", required=True, type=Path)
    parser.add_argument("--stored", required=True, type=Path)
    args = parser.parse_args()
    replay = json.loads(args.replay.read_text(encoding="utf-8-sig"))
    original = {item["id"]: item for item in json.loads(args.stored.read_text(encoding="utf-8-sig"))}
    dataset = "/".join(args.stored.parts[-2:])
    samples = [item for item in replay if item["dataset"] == dataset and item["id"] in original]
    assert samples, "matching stored notices are required"
    results = []
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel="msedge", headless=True)
        try:
            for sample in samples:
                results.append(await check(browser, sample, original[sample["id"]]))
        finally:
            await browser.close()
    (OUT / "actual-source-only-summary.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("PASS stored reported notices: " + json.dumps(results))


asyncio.run(main())
