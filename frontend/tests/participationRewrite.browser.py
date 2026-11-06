"""Server-owned eligibility wording, empty states and interactive table regression.
All APIs are mocked GET requests; extraction, retries and paid model APIs are blocked.
"""
import asyncio
import copy
import importlib.util
import re
import sys
from pathlib import Path

from playwright.async_api import async_playwright, expect

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("businessSummary.browser.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)
OUT = Path("frontend/dist/participation-rewrite-checks")
AGE = "공고일 기준 업력 7년 이상의 중소기업이어야 합니다."
AGE_QUOTE = "□ (신청자격) 공고일 기준 업력 7년 이상 중소기업\n※ 단, 추천기업은 업력 7년 미만도 가능"
AGE_NOTE = "단, 추천기업은 업력 7년 미만도 신청할 수 있습니다."
REVENUE = "최근 3개년 매출액 100억원을 1회 이상 달성한 기업이어야 합니다."
UNRESOLVED = "[표]\n신청자격 및 신청요건\t세부 적용 대상은 원문 참조"


def entry(text, section="BASIC", verified=True, extra_sources=False, scope=None, quote=None, audience=None, notes=None):
    source = dict(origin="NOTICE_BODY", attachmentName=None, sectionTitle="신청자격 및 신청요건", location=None, excerpt=quote or text)
    sources = [source]
    if extra_sources:
        sources.append(dict(source, origin="ATTACHMENT", attachmentName="사업 공고문.pdf", location="3쪽"))
    return dict(displayStatus="VERIFIED" if verified else "EVIDENCE_ONLY", context=None, relation=None, relatedCriteria=[],
                rule=dict(section=section, label="조건", text=text, scope=scope, audience=audience,
                          details=[], exceptions=notes or [], guidance=[], outcomes=[], sources=sources))


def guide(status="READY"):
    verified = status == "READY"
    conditions = [
        ("BUSINESS_FORM", entry("창업기업 지원역량을 보유한 공공기관 또는 민간기관이 지원 대상입니다.", verified=verified, audience="주관기관")),
        ("BUSINESS_AGE", entry(AGE, verified=verified, extra_sources=True, scope="공통", quote=AGE_QUOTE, notes=[AGE_NOTE])),
        ("REVENUE", entry(REVENUE, verified=verified, scope="점프업100", notes=["기준 기간은 2023~2025년입니다."])),
        ("IP", entry("등록 특허를 보유한 기업은 가점 2점을 부여합니다.", "PREFERENCE", verified)),
        ("SECTOR", entry("제조업(C10~C34)을 주된 사업으로 영위해야 합니다.", verified=verified)),
        ("EXPERIENCE", entry("최근 1년간 공공기관 계약실적은 10억원 이하여야 합니다.", verified=verified)),
        ("CREDIT", entry("기업신용평가등급이 B 미만인 기업은 신청할 수 없습니다.", "EXCLUSION", verified)),
    ]
    rows = [dict(code=code, label=code, status="STATED", entries=[value]) for code, value in conditions]
    # Unselected sources may repeat in the response but must not add a standalone card.
    for row in rows:
        row["entries"] += [entry("", "EVIDENCE", False, quote=UNRESOLVED)]
    return dict(conditions=[], rules=[], variants=[], rewriteStatus=status,
                categories=[dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=rows)])


async def assert_responsive_condition_table(region, width):
    headers = ["자격 항목", "구분", "해당 사업", "신청 조건"] if width > 720 else ["자격 항목", "구분", "신청 조건"]
    await expect(region.get_by_role("columnheader")).to_have_text(headers)
    assert await region.locator("tbody tr").evaluate_all("""(rows, columns) => rows.every(row =>
        [...row.querySelectorAll('td')].filter(cell => cell.getClientRects().length > 0).length === columns)
    """, 3 if width > 720 else 2), (width, "visible condition columns")
    content = region.locator(".participation-guide__program:visible, .participation-guide__audience:visible, .participation-guide__main, .participation-guide__notes:visible, .participation-guide__notes:visible li, .participation-guide__actions button")
    assert await content.evaluate_all("""elements => elements.every(element => {
        const box = element.getBoundingClientRect();
        const cell = element.closest('td').getBoundingClientRect();
        return element.scrollHeight <= element.clientHeight + 1 && element.scrollWidth <= element.clientWidth + 1
            && box.left >= cell.left - 1 && box.right <= cell.right + 1;
    })"""), (width, "full type, role and condition text")
    assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), width


async def assert_collapsed_notes(region):
    buttons = region.locator(".participation-guide__notes-button")
    await expect(buttons).to_have_count(await region.locator(".participation-guide__notes").count())
    for button in await buttons.all():
        panel = region.locator(f'[id="{await button.get_attribute("aria-controls")}"]')
        count = await panel.locator("li").count()
        assert count > 0
        await expect(button).to_have_attribute("aria-expanded", "false")
        await expect(button).to_have_attribute("aria-label", re.compile(rf" 예외·추가 조건 {count}개 펼치기$"))
        await expect(button).to_contain_text("예외·추가 조건")
        await expect(button.locator(".participation-guide__notes-count")).to_have_text(str(count))
        await expect(panel).to_be_hidden()


async def set_all_notes(region, opened):
    for button in await region.locator(".participation-guide__notes-button").all():
        panel = region.locator(f'[id="{await button.get_attribute("aria-controls")}"]')
        originals = await panel.locator("li").all_text_contents()
        if (await button.get_attribute("aria-expanded") == "true") != opened:
            await button.click()
        await expect(button).to_have_attribute("aria-expanded", str(opened).lower())
        if opened:
            await expect(panel).to_be_visible()
            await expect(panel.locator("li")).to_have_text(originals)
        else:
            await expect(panel).to_be_hidden()


async def check(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["participationGuide"] = guide("PENDING")
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        await expect(region.get_by_role("table")).to_have_count(0)
        await expect(region.get_by_role("status")).to_be_visible()
        await expect(region.locator(".participation-guide__unresolved")).to_have_count(0)
        await expect(region.locator("blockquote")).to_have_count(0)
        before = h.counts[1]
        ready = copy.deepcopy(data)
        ready["participationGuide"] = guide()
        h.states[1] = ready
        await h.page.clock.run_for(3100)
        await h.wait_request(1, before + 1)
        expected = [value["entries"][0]["rule"]["text"] for value in ready["participationGuide"]["categories"][0]["rows"]]
        await expect(region.locator(".participation-guide__main")).to_have_text(expected)
        await expect(region.get_by_role("status")).to_have_count(0)
        await expect(region.locator(".participation-guide__unresolved")).to_have_count(0)
        await expect(region.locator(".participation-guide__evidence-button")).to_have_count(7)
        await expect(region.locator('[data-condition-kind="preference"]')).to_have_count(1)
        await expect(region.locator('[data-condition-kind="exclusion"]')).to_have_count(1)
        await expect(region.locator(".participation-guide__unscoped:visible")).to_have_count(5)
        await expect(region.locator(".participation-guide__audience:visible")).to_have_text(["주관기관"])
        await region.get_by_role("navigation", name="신청 자격 항목 바로가기", exact=True).get_by_role("link", name="매출·수출", exact=True).click()
        await expect(region.locator('[data-criterion="REVENUE"]')).to_be_focused()
        await assert_collapsed_notes(region)

        notes = region.get_by_role("button", name=re.compile(r"^업력 필수 조건 예외·추가 조건 1개 (?:펼치기|접기)$"))
        age_notes = region.locator('[data-criterion="BUSINESS_AGE"] .participation-guide__notes')
        evidence = region.get_by_role("button", name=re.compile(r"^업력 필수 조건 원문 근거 (?:확인|접기)$"))
        await notes.focus()
        await notes.press("Enter")
        await expect(age_notes.locator("li")).to_have_text([AGE_NOTE])
        await evidence.press("Enter")
        await expect(region.locator("blockquote")).to_have_text(AGE_QUOTE)
        await expect(age_notes).to_be_visible()
        await region.get_by_label("원문 선택", exact=True).select_option("1")
        await expect(region.get_by_role("link", name="근거 문서 열기")).to_have_attribute("href", "https://example.org/notice.pdf")
        await notes.press("Space")
        await expect(age_notes).to_be_hidden()
        await expect(region.locator("blockquote")).to_be_visible()
        await evidence.press("Space")
        await expect(region.locator("blockquote")).to_have_count(0)
        await h.stopped()
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            await assert_responsive_condition_table(region, width)
            await expect(region.locator(".participation-guide__main")).to_have_text(expected)
            await expect(region.locator("tbody tr")).to_have_count(7)
            await region.evaluate("""element => {
                const drawer = element.closest('.drawer');
                drawer.scrollTop += element.getBoundingClientRect().top - drawer.getBoundingClientRect().top
                    - drawer.querySelector('.drawer__top').offsetHeight - 12;
            }""")
            await h.page.screenshot(path=str(OUT / f"verified-{width}.png"))
            await notes.press("Enter")
            await evidence.press("Enter")
            await expect(age_notes).to_be_visible()
            await expect(region.locator("blockquote")).to_have_text(AGE_QUOTE)
            await assert_responsive_condition_table(region, width)
            await evidence.press("Space")
            await notes.press("Space")
        await notes.press("Enter")
        await evidence.press("Enter")
        second = copy.deepcopy(ready)
        second.update(summary.refresh.row(2))
        h.states[2] = second
        await h.page.get_by_role("button", name="목록으로", exact=True).click()
        await h.open(identifier=2, analysis_tab=False)
        await expect(region.locator("blockquote")).to_have_count(0)
        await expect(region.locator('button[aria-expanded="true"]')).to_have_count(0)
        await assert_collapsed_notes(region)
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def source_only_states(browser):
    for status in ("READY", "UNAVAILABLE", None):
        h = await summary.refresh.Harness(browser).start()
        try:
            data = summary.ready()
            value = entry("", "EVIDENCE", False, extra_sources=True, quote=UNRESOLVED)
            data["participationGuide"] = dict(conditions=[], rewriteStatus=status, categories=[
                dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[
                    dict(code="BUSINESS_FORM", label="신청 주체", status="REVIEW_REQUIRED", entries=[copy.deepcopy(value) for _ in range(28)])
                ])]) if status else None
            h.states[1] = data
            await h.open(analysis_tab=False)
            region = h.page.get_by_role("region", name="신청 자격", exact=True)
            await expect(region.get_by_role("table")).to_have_count(0)
            await expect(region.get_by_role("status")).to_have_count(0)
            await expect(region).to_contain_text("완료하지 못했습니다" if status == "UNAVAILABLE" else "정리된 신청 조건이 없습니다")
            await expect(region).not_to_contain_text("재시도")
            await expect(region.locator("blockquote")).to_have_count(0)
            await expect(region.locator(".participation-guide__unresolved")).to_have_count(0)
            await expect(region.get_by_role("button")).to_have_count(0)
            for width in (1440, 390, 320):
                await h.page.set_viewport_size(dict(width=width, height=1000))
                assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), (status, width)
                await region.scroll_into_view_if_needed()
                await h.page.screenshot(path=str(OUT / f"source-only-{status}-{width}.png"))
            await h.stopped()
            assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
        finally:
            await h.close()


async def server_text_and_exact_duplicates(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        text = '기관은 <img src=x onerror="window.__injected=true"> 자격을 확인해야 합니다.'
        first = entry(text, scope="모델이 제공한 유형", audience="모델이 제공한 역할")
        second = copy.deepcopy(first)
        second["rule"]["sources"] = [dict(first["rule"]["sources"][0], origin="ATTACHMENT",
                                         attachmentName="사업 공고문.pdf", excerpt="다른 형식의 실제 원문")]
        different = copy.deepcopy(first)
        different["rule"]["text"] = "기관은 자격을 확인해야 합니다."
        different_scope = copy.deepcopy(first)
        different_scope["rule"]["scope"] = "다른 유형"
        data["participationGuide"] = dict(conditions=[], rewriteStatus="READY", categories=[
            dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[
                dict(code="BUSINESS_FORM", label="신청 주체", status="STATED", entries=[first, second, different, different_scope])
            ])])
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        await expect(region.locator(".participation-guide__main")).to_have_text([text, different["rule"]["text"], text])
        await expect(region.locator("img")).to_have_count(0)
        assert await h.page.evaluate("window.__injected === undefined")
        await region.locator(".participation-guide__evidence-button").first.press("Enter")
        await expect(region.get_by_label("원문 선택", exact=True).locator("option")).to_have_count(2)
        await region.get_by_label("원문 선택", exact=True).select_option("1")
        await expect(region.locator("blockquote")).to_have_text("다른 형식의 실제 원문")
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def selected_program_retains_common_conditions(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        entries = [entry(f"특허 {index + 1}건을 보유한 중소기업이 지원 대상입니다.", scope=f"사업유형{index + 1}") for index in range(6)]
        entries += [entry("국내 중소기업이 지원 대상입니다.", scope="공통"), entry("기업부설연구소를 보유한 기업이 지원 대상입니다.")]
        entries[2]["rule"]["details"] = ["최근 3개년(’23~’25년) 실적을 기준으로 확인"]
        entries[2]["rule"]["exceptions"] = ["창업 3년 미만 추천기업도 신청대상에 포함"]
        entries[2]["rule"]["sources"][0]["excerpt"] += "\n" + "\n".join(entries[2]["rule"]["details"] + entries[2]["rule"]["exceptions"])
        data["participationGuide"] = dict(conditions=[], rules=[], variants=[], rewriteStatus="READY", categories=[
            dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[dict(code="BUSINESS_FORM", label="신청 주체", status="STATED", entries=entries)])])
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        await expect(region.locator(".participation-guide__main")).to_have_count(8)
        before = h.counts[1]
        await region.get_by_label("해당 사업", exact=True).select_option("사업유형3")
        await expect(region.locator(".participation-guide__main")).to_have_count(3)
        await expect(region.locator(".participation-guide__program:visible")).to_have_text(["사업유형3", "전체 사업 공통"])
        await expect(region.locator(".participation-guide__filter-count")).to_have_text("3개 표시 / 전체 8개 조건")
        await assert_collapsed_notes(region)
        await set_all_notes(region, True)
        await expect(region.locator(".participation-guide__notes")).to_be_visible()
        await expect(region.locator(".participation-guide__notes li")).to_have_text(["최근 3개년(’23~’25년) 실적을 기준으로 확인", "창업 3년 미만 추천기업도 신청대상에 포함"])
        await expect(region.locator("blockquote")).to_have_count(0)
        await expect(region).to_contain_text("기업부설연구소를 보유한 기업이 지원 대상입니다.")
        await region.get_by_role("button", name="전체 보기", exact=True).focus()
        await h.page.keyboard.press("Enter")
        await expect(region.locator(".participation-guide__main")).to_have_count(8)
        assert h.counts[1] == before
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def disappearing_program_filter_restores_all_conditions(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        entries = [entry(f"특허 {index + 1}건을 보유한 중소기업이 지원 대상입니다.", scope=f"사업유형{index + 1}") for index in range(8)]
        data["participationGuide"] = dict(conditions=[], rules=[], variants=[], rewriteStatus="PENDING", categories=[
            dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[dict(code="BUSINESS_FORM", label="신청 주체", status="STATED", entries=entries)])])
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        await expect(region.locator(".participation-guide__main")).to_have_count(8)
        await region.get_by_label("해당 사업", exact=True).select_option("사업유형3")
        await expect(region.locator(".participation-guide__main")).to_have_count(1)
        updated = copy.deepcopy(data)
        updated["participationGuide"]["rewriteStatus"] = "READY"
        updated["participationGuide"]["categories"][0]["rows"][0]["entries"] = entries[:3]
        h.states[1] = updated
        before = h.counts[1]
        await h.page.clock.run_for(3100)
        await h.wait_request(1, before + 1)
        await expect(region.get_by_label("해당 사업", exact=True)).to_have_count(0)
        await expect(region.locator(".participation-guide__main")).to_have_count(3)
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def cached_business_labels(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        text = "창업도시 조성 프로젝트를 추진하는 광역자치단체(시·도)를 모집합니다."
        values = [
            entry(text, scope="실제 사업·과제유형(명시공통='공통')", audience="신청역할"),
            entry("신청기관은 지원역량을 보유해야 합니다.", scope="공통", audience="주관기관"),
            entry("신청기업은 업력 7년 이내여야 합니다.", scope="초기창업패키지 · 일반형", audience="창업기업"),
        ]
        data["participationGuide"] = dict(conditions=[], rules=[], variants=[], rewriteStatus="READY", categories=[
            dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[
                dict(code="BUSINESS_FORM", label="신청 주체", status="STATED", entries=values)])])
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        for width in (1440, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            await assert_responsive_condition_table(region, width)
            await expect(region).not_to_contain_text("실제 사업·과제유형")
            await expect(region).not_to_contain_text("신청역할")
            await expect(region).not_to_contain_text("적용 유형")
            await expect(region.locator(".participation-guide__unscoped:visible")).to_have_text("원문 확인 필요")
            await expect(region.locator(".participation-guide__program:visible")).to_have_text(["전체 사업 공통", "초기창업패키지 · 일반형"])
            await expect(region.locator(".participation-guide__audience:visible")).to_have_text(["주관기관", "창업기업"])
            await expect(region.locator(".participation-guide__role:visible")).to_have_text(["신청 대상: 주관기관", "신청 대상: 창업기업"])
            await expect(region.locator(".participation-guide__main")).to_have_text([value["rule"]["text"] for value in values])
            await region.scroll_into_view_if_needed()
            await region.screenshot(path=str(OUT / f"business-labels-{width}.png"))
        await region.locator(".participation-guide__evidence-button").first.press("Enter")
        await expect(region.locator("blockquote")).to_have_text(text)
        await h.stopped()
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def business_scope_absence_and_uncertainty(browser):
    for case, scope, variants, expected in [
        ("no-division", None, [], "-"),
        ("cached-description", "실제 사업·과제유형(명시공통='공통')", [], "-"),
        ("explicit-review", "확인 필요", [], "원문 확인 필요"),
        ("declared-types", None, [dict(name="유형1", description="일반형"), dict(name="유형2", description="성장형")], "원문 확인 필요"),
    ]:
        h = await summary.refresh.Harness(browser).start()
        try:
            data = summary.ready()
            text = "창업도시 조성 프로젝트를 추진하는 광역자치단체(시·도)를 모집합니다."
            data["participationGuide"] = dict(conditions=[], rules=[], variants=variants, rewriteStatus="READY", categories=[
                dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[
                    dict(code="BUSINESS_FORM", label="신청 주체", status="STATED", entries=[entry(text, scope=scope, audience="광역자치단체")])])])
            h.states[1] = data
            await h.open(analysis_tab=False)
            region = h.page.get_by_role("region", name="신청 자격", exact=True)
            for width in (1440, 390, 320):
                await h.page.set_viewport_size(dict(width=width, height=1100))
                await assert_responsive_condition_table(region, width)
                await expect(region.locator(".participation-guide__unscoped:visible")).to_have_text(expected)
                await expect(region.locator(".participation-guide__main")).to_have_text(text)
                await expect(region.locator(".participation-guide__program:visible")).to_have_count(0)
                await expect(region.locator(".participation-guide__filter")).to_have_count(0)
                await region.scroll_into_view_if_needed()
                await region.screenshot(path=str(OUT / f"scope-{case}-{width}.png"))
            await region.locator(".participation-guide__evidence-button").press("Enter")
            await expect(region.locator("blockquote")).to_have_text(text)
            await h.stopped()
            assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
        finally:
            await h.close()


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel="msedge", headless=True)
        try:
            await check(browser)
            await cached_business_labels(browser)
            await business_scope_absence_and_uncertainty(browser)
            await source_only_states(browser)
            await server_text_and_exact_duplicates(browser)
            await selected_program_retains_common_conditions(browser)
            await disappearing_program_filter_restores_all_conditions(browser)
        finally:
            await browser.close()
    print("PASS seven server-owned fields; row evidence retained with no standalone card; empty/failed/pending states; exact duplicate citations; escaped text; independent notes/evidence; keyboard; four widths; filters without API calls; terminal polling stopped.")


asyncio.run(main())
