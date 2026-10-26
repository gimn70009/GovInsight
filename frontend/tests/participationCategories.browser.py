"""Eight fixed category tables, real resolver output, disclosure behavior and narrow layouts."""
import asyncio
import importlib.util
import json
import re
import sys
from pathlib import Path
from playwright.async_api import async_playwright, expect

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("category_summary", Path(__file__).with_name("businessSummary.browser.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)
OUT = Path("frontend/dist/participation-category-checks")


async def check(browser, path, name):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["participationGuide"] = json.loads(path.read_text(encoding="utf-8")) if path else None
        guide_data = data["participationGuide"]
        if guide_data:
            filenames = {source["attachmentName"] for category in guide_data["categories"]
                         for row in category["rows"] for entry in row["entries"]
                         for source in entry["rule"]["sources"] if source.get("attachmentName")}
            data["attachments"] = [dict(fileName=file, fileExtension="pdf", fileSize=1024, parseStatus="COMPLETED",
                                        downloadUrl="https://example.org/notice.pdf") for file in sorted(filenames)]
        h.states[1] = data
        await h.open(analysis_tab=False)
        guide = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
        await expect(guide.get_by_role("table")).to_have_count(8)
        assert await guide.locator("tbody tr:not([data-criterion=OTHER])").count() == 40
        await expect(guide.get_by_role("heading", name="8. 우대·가점 항목")).to_have_count(1)
        if name == "example":
            age = guide.locator('[data-criterion="BUSINESS_AGE"]')
            certification = guide.locator('[data-criterion="CERTIFICATION"]')
            await expect(age.locator(".participation-guide__main")).to_contain_text("창업 1년 이상")
            await expect(age.locator(".participation-guide__main")).not_to_contain_text("인정서")
            await expect(certification.locator(".participation-guide__main")).to_contain_text("기업부설연구소")
            await expect(guide.locator('[data-criterion="REVENUE"]')).to_contain_text("공고에 명시 없음")
            await expect(guide.locator('[data-criterion="PREF_REPRESENTATIVE"]')).to_contain_text("여성기업")
            button = age.get_by_role("button", name="업력·창업 세부 조건")
            await button.focus()
            await button.press("Enter")
            await expect(age.locator(".participation-guide__context")).to_contain_text("접수마감일")
            await expect(age.locator(".participation-guide__context")).to_contain_text("기업부설연구소")
            evidence = age.get_by_role("button", name="근거 보기")
            await evidence.press("Space")
            await expect(button).to_have_attribute("aria-expanded", "false")
            await expect(age.locator("blockquote")).to_contain_text("창업 1년 이상")
            await evidence.press("Enter")
            await expect(age.locator("blockquote")).to_have_count(0)
        elif name in ("japan-full", "japan-flat"):
            consortium = guide.locator('[data-criterion="CONSORTIUM"]')
            await expect(consortium).to_contain_text("2개 이상 국가")
            await expect(consortium).to_contain_text("일본 참여 필수")
            await expect(consortium).not_to_contain_text("서류 유형")
            await expect(guide.locator('[data-criterion="PLAN"]')).not_to_contain_text("접수 마감")
            await expect(guide.locator('[data-criterion="DUPLICATE"]')).to_contain_text("최대 5개 이내여야 하며")
            await expect(guide.locator('[data-criterion="DUPLICATE"]')).to_contain_text("최대 3개 이내여야 합니다")
        elif name == "ax-research-hwpx":
            await expect(guide.locator('[data-criterion="CAPITAL"]')).to_contain_text("혼용할 수 없음)")
            await expect(guide.locator('[data-criterion="PLAN"]')).not_to_contain_text("평가위원회 (발표평가)")
            await expect(guide.locator('[data-criterion="MATCHING"]')).not_to_contain_text("통합RCMS")
        elif name == "readable-tiers":
            sizes = guide.locator('[data-criterion="COMPANY_SIZE"] .participation-guide__main')
            await expect(sizes).to_have_count(3)
            for index, amount in enumerate((100, 500, 1000)):
                await expect(sizes.nth(index)).to_contain_text(f"유형{index + 1}:")
                await expect(sizes.nth(index)).to_contain_text(f"매출액 {amount}억원")
                await expect(sizes.nth(index)).to_contain_text("중소기업이 대상입니다.")
            for code in ("BUSINESS_AGE", "REVENUE"):
                statements = guide.locator(f'[data-criterion="{code}"] .participation-guide__main')
                for value in await statements.all_text_contents():
                    assert value.strip().endswith("니다."), (code, value)
            await expect(guide.locator(".participation-guide__scope, .participation-guide__audience")).to_have_count(0)
            await expect(guide.get_by_text("아래 지원제외 대상에 해당하는 기업", exact=True)).to_have_count(0)
            await expect(guide.get_by_text("구분 제외 대상", exact=True)).to_have_count(0)
            plan = guide.locator('[data-criterion="PLAN"] .participation-guide__main')
            await expect(plan).to_have_count(3)
            assert all(value.strip().endswith("니다.") for value in await plan.all_text_contents())
        elif name in ("clean-display", "clean-flat-display"):
            statements = await guide.locator(".participation-guide__main").all_text_contents()
            for sentence in statements:
                assert sentence.strip().endswith("니다."), sentence
                assert not re.search(r"(?:^|\s)(?:[가나다라마바사아자차카타파하]\.|[①-⑳]|\[참고\d+\])", sentence), sentence
                assert all(value not in sentence for value in ("이 법에서", "각 호의 요건", "・・", "650내외개사", "영위할 것", "신청 영위", " 조건.", "<")), sentence
            matching = guide.locator('[data-criterion="MATCHING"]')
            await expect(matching.locator(".participation-guide__main").filter(has_text="지원방식은")).to_have_count(1)
            await expect(matching).to_contain_text("모든 유형 공통으로")
            await expect(matching).to_contain_text("국비는 70% 이내, 자부담은 30% 이상입니다.")
            if name == "clean-display":
                for variant, grant, contribution in (("일반", "630만원 이내", "270만원 이상"), ("고위험업종", "1,050만원 이내", "450만원 이상")):
                    row = matching.locator(".participation-guide__main").filter(has_text=f"안전환경({variant})")
                    await expect(row).to_have_count(1)
                    await expect(row).to_contain_text(grant)
                    await expect(row).to_contain_text(contribution)
            else:
                await expect(matching.locator(".participation-guide__main")).to_have_count(1)
                await expect(matching).not_to_contain_text("630만원")
            await expect(guide.locator('[data-criterion="SECTOR"]')).to_contain_text("고위험업종 유형으로 신청하려면 산재보험 가입 코드가 고위험업종 코드와 일치해야 합니다.")
            await expect(guide.locator('[data-criterion="HEADCOUNT"] .participation-guide__main').filter(has_text="10명 미만")).to_have_count(1)
            evidence = matching.locator(".participation-guide__rule").filter(has_text="지원방식은").get_by_role("button", name="근거 보기")
            await evidence.click()
            await expect(matching.locator("blockquote")).to_contain_text("라. 지원방식")
            await evidence.click()
        elif name == "missing":
            assert await guide.get_by_text("—", exact=True).count() == 40
            assert await guide.get_by_text("공고에 명시 없음", exact=True).count() == 0
        if name in ("japan-full", "japan-flat", "readable-tiers"):
            plan = guide.locator('[data-criterion="PLAN"] .participation-guide__main')
            await expect(plan).to_have_count(3)
            for text in await plan.all_text_contents():
                assert all(word not in text for word in ("사업 절차", "접수 마감", "k-pass.kr", "[표]"))
                assert text.strip().endswith("니다."), text
        await expect(guide.get_by_text("원문 확인 필요", exact=True)).to_have_count(0)
        await expect(guide.get_by_text("같은 문장에서 나눈 조건", exact=True)).to_have_count(0)
        if guide_data:
            # Every source and every explicit condition stays reachable, including optional and exclusion criteria.
            for category in guide_data["categories"]:
                for row in category["rows"]:
                    rendered = guide.locator(f'table[aria-label="{category["label"]}"] [data-criterion="{row["code"]}"]')
                    assert await rendered.locator(".participation-guide__rule").count() == len([entry for entry in row["entries"] if entry["rule"]["section"] != "REVIEW"])
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await guide.locator(".participation-guide__section").first.scroll_into_view_if_needed()
            assert await h.page.locator(".drawer--detail").evaluate("el => el.scrollWidth <= el.clientWidth"), (name, width)
            assert await guide.evaluate("el => el.scrollWidth <= el.clientWidth"), (name, width)
            assert await guide.locator(".participation-guide__main").evaluate_all(
                "els => els.every(el => el.scrollHeight <= el.clientHeight + 1 && getComputedStyle(el).textOverflow !== 'ellipsis')"), (name, width)
            if width in (1440, 390) and name in ("example", "japan-full", "japan-flat", "readable-tiers", "clean-display", "clean-flat-display"):
                OUT.mkdir(parents=True, exist_ok=True)
                await h.page.screenshot(path=str(OUT / f"{name}-{width}.png"))
                if name.startswith("clean-"):
                    await guide.locator('[data-criterion="SECTOR"]').scroll_into_view_if_needed()
                    await h.page.screenshot(path=str(OUT / f"{name}-sector-{width}.png"))
                    await guide.locator('[data-criterion="MATCHING"]').scroll_into_view_if_needed()
                    await h.page.screenshot(path=str(OUT / f"{name}-funding-{width}.png"))
                if name in ("japan-flat", "readable-tiers"):
                    await guide.locator('[data-criterion="PLAN"]').scroll_into_view_if_needed()
                    await h.page.screenshot(path=str(OUT / f"{name}-execution-{width}.png"))
        print(f"PASS {name}: 8 tables, 40 fixed rows, disclosures, widths 1440/768/390/320")
    finally:
        await h.close()


async def main():
    paths = [(Path("backend/build/participation-categories/japan-example.json"), "example"), (None, "missing")]
    for name in sys.argv[1:]:
        paths.append((Path(f"backend/build/participation-categories/{name}.json"), name))
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel="msedge", headless=True)
        try:
            for path, name in paths:
                await check(browser, path, name)
        finally:
            await browser.close()


asyncio.run(main())
