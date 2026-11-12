"""Check corrected stored eligibility projections with mocked GET APIs only."""
import argparse
import asyncio
import importlib.util
import json
import re
import sys
from pathlib import Path

from playwright.async_api import async_playwright, expect

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("businessSummary.browser.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)
OUT = Path("frontend/dist/participation-classification-checks")
SUPPORTED = ["BUSINESS_FORM", "BUSINESS_AGE", "REVENUE", "IP", "SECTOR", "EXPERIENCE", "CREDIT"]
REQUIRED = ["BASIC", "ELIGIBILITY", "TARGETED"]
WRONG_CONDITIONS = ["총14개내외", "총사업비의30~100%", "지원기간지원한도지원비율", "정부지원기준과민간부담비율"]


def visible_entries(guide):
    entries = []
    for code in SUPPORTED:
        candidates = [entry for category in guide.get("categories", []) for row in category["rows"]
                      if row["code"] == code for entry in row["entries"]]
        groups = {}
        for entry in candidates:
            rule = entry["rule"]
            if entry.get("displayStatus") != "VERIFIED" or not rule["text"].strip():
                continue
            if rule["section"] not in REQUIRED + ["PREFERENCE"]:
                continue
            key = json.dumps([rule.get(field) for field in
                              ("section", "text", "scope", "audience", "details", "exceptions", "guidance", "outcomes")]
                             + [entry.get(field) for field in ("context", "relation", "relatedCriteria")],
                             ensure_ascii=False)
            groups[key] = entry
        entries += [entry for entry in groups.values() if entry["rule"]["section"] in REQUIRED]
        entries += [entry for entry in groups.values() if entry["rule"]["section"] == "PREFERENCE"]
    exclusions = {}
    for category in guide.get("categories", []):
        for row in category["rows"]:
            for entry in row["entries"]:
                rule = entry["rule"]
                if entry.get("displayStatus") == "VERIFIED" and rule["text"].strip() and rule["section"] == "EXCLUSION":
                    key = json.dumps([rule.get(field) for field in
                        ("section", "text", "scope", "audience", "details", "exceptions", "guidance", "outcomes")]
                        + [entry.get(field) for field in ("context", "relation", "relatedCriteria")], ensure_ascii=False)
                    exclusions[key] = entry
    return entries + list(exclusions.values())


async def check(browser, sample, original, readable=False, required_ids=()):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["title"] = sample.get("title", original["title"])
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
        expected = visible_entries(sample["guide"])
        sentences = [entry["rule"]["text"] for entry in expected]
        await expect(region.locator(".participation-guide__main")).to_have_text(sentences)
        compact = [re.sub(r"\s+", "", text) for text in sentences]
        assert not any(term in text for term in WRONG_CONDITIONS for text in compact), (sample["id"], sentences)
        if sample["id"] in required_ids:
            assert expected, (sample["id"], "reported notice must recover readable conditions")
        if readable:
            assert all(re.search(r"(?:다|요)[.!?]$", text.strip()) for text in sentences), (sample["id"], "unfinished condition", sentences)
            assert not any(mark in text for text in compact for mark in ("민민간간기기관관", "B이상상", "[표]", "[/표]")), (sample["id"], "damaged condition", sentences)
            assert not any("\t" in text or re.match(r"^\s*[-*□■]", text) for text in sentences), (sample["id"], "raw source fragment used as a condition", sentences)
        await expect(region.locator('[data-condition-kind="review"]')).to_have_count(0)
        await expect(region.locator("blockquote")).to_have_count(0)
        await expect(region.locator(".participation-guide__unresolved")).to_have_count(0)
        for button in await region.locator(".participation-guide__condition-toggle").all():
            await expect(button).to_have_attribute("aria-expanded", "false")
        if await region.locator("[data-criterion]").count() >= 4:
            await expect(region.get_by_role("navigation", name="신청 자격 항목 바로가기", exact=True)).to_be_visible()
            await expect(region.locator(".participation-guide__index-label")).to_have_text("자격 항목 바로가기")
        await expect(region.get_by_role("status")).to_have_count(0)
        await h.page.clock.run_for(300)
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            if expected:
                await expect(region.get_by_role("table")).to_have_count(0)
                await expect(region.locator(".participation-guide__rule")).to_have_count(len(expected))
                scopes = [entry["rule"]["scope"] for entry in expected if (entry["rule"].get("scope") or "").strip() not in ("", "유형명")]
                await expect(region.locator(".participation-guide__program:visible")).to_have_text([scope.replace("공통", "전체 사업 공통") if scope == "공통" else scope for scope in scopes])
                await expect(region.locator(".participation-guide__audience:visible")).to_have_count(0)
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), (sample["id"], width)
            await region.evaluate("""element => {
                const drawer = element.closest('.drawer');
                drawer.scrollTop += element.getBoundingClientRect().top - drawer.getBoundingClientRect().top
                    - drawer.querySelector('.drawer__top').offsetHeight - 12;
            }""")
            await h.page.screenshot(path=str(OUT / f"corrected-{sample['id']}-{width}.png"))

        # Each displayed condition retains its source evidence; there is no standalone unresolved card.
        original_excerpts = {
            source["excerpt"] for category in sample["guide"].get("categories", []) for row in category["rows"]
            if row["code"] in SUPPORTED for entry in row["entries"]
            if entry.get("displayStatus") == "VERIFIED" and entry["rule"]["text"].strip()
            and entry["rule"]["section"] in REQUIRED + ["PREFERENCE", "EXCLUSION"]
            for source in entry["rule"]["sources"] if (source.get("excerpt") or "").strip()
        }
        displayed_excerpts = set()
        allowed_urls = {item["url"] for item in original["attachments"]}
        for button in await region.locator(".participation-guide__condition-toggle").all():
            await button.press("Enter")
            evidence_toggle = region.locator('.participation-guide__source-toggle[aria-expanded="false"]')
            if await evidence_toggle.count():
                await expect(region.locator('blockquote')).to_have_count(0)
                await evidence_toggle.press('Enter')
            selector = region.get_by_label("원문 선택", exact=True)
            options = await selector.locator("option").count()
            for index in range(max(1, options)):
                if options:
                    await selector.select_option(str(index))
                excerpt = await region.locator("blockquote").text_content()
                displayed_excerpts.add(excerpt)
                link = region.get_by_role("link", name="근거 문서 열기")
                if await link.count():
                    assert await link.get_attribute("href") in allowed_urls
            await button.press("Space")
        assert original_excerpts <= displayed_excerpts, (sample["id"], "source excerpts missing")
        await expect(region.locator("blockquote")).to_have_count(0)
        await h.stopped()
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
        return dict(id=sample["id"], conditionRows=len(expected), originalExcerpts=len(displayed_excerpts),
                    scopes=list(dict.fromkeys(entry["rule"].get("scope") for entry in expected)),
                    viewports=[1440, 390, 320], readableChecked=readable, paidApiCalls=0)
    finally:
        await h.close()


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--replay", required=True, type=Path)
    parser.add_argument("--stored", required=True, type=Path)
    parser.add_argument("--readable", action="store_true", help="Require complete display sentences; original evidence stays untouched")
    parser.add_argument("--require-conditions", nargs="*", type=int, default=[], help="Reported notice ids whose conditions must be restored")
    args = parser.parse_args()
    replay = json.loads(args.replay.read_text(encoding="utf-8-sig"))
    originals = {item["id"]: item for item in json.loads(args.stored.read_text(encoding="utf-8-sig"))}
    samples = [item for item in replay if item["id"] in originals and item.get("guide")]
    assert samples, "matching corrected guides are required"
    results = []
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel="msedge", headless=True)
        try:
            for sample in samples:
                results.append(await check(browser, sample, originals[sample["id"]], args.readable, args.require_conditions))
        finally:
            await browser.close()
    (OUT / "corrected-summary.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS corrected stored eligibility projection: " + json.dumps(results, ensure_ascii=False))


asyncio.run(main())
