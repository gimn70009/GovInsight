"""Eligibility conditions: readable tables, preserved source evidence, responsive UI.
Only mocked GET APIs are used; no analysis or model API can be called.
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
AGE = "**□ (신청자격) ｢중소기업기본법｣ 제2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업**\n※ ｢중소기업창업지원법｣ 제2조에 따른 창업자에 해당하지 않는 업력 7년 미만의 중소기업 및 점프업100의 마일스톤트랙 추천기업은 신청대상에 포함"
AGE_LEAD = "신청 자격은 중소기업기본법 제 2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업"
REVENUE = "□ (지원대상) 업력 7년 이상의 매출액 100억원 달성* 중소기업\n* 최근 3개년(’23~’25년) 표준재무제표증명원상 매출액 100억원 1회 이상 달성 기업\n** 마일스톤트랙 : 매출 100억원을 달성한 창업 3년 미만인 창업지원 사업 참여기업을 대상으로, 창업지원 사업 운영기관의 추천을 통해 별도 선발(10개사 내)"
REVENUE_LEAD = "지원 대상은 업력 7년 이상의 매출액 100억원 달성 중소기업"
PATENT = "관련 등록특허 보유기업은 가점 2점"
SECTOR = "제조업 중 산업용 로봇·인공지능 분야의 기술을 보유한 중소기업 (기술 적용 비율 ≥ 50%, 매출 성장률 = (당해 매출 - 전년 매출) / 전년 매출 × 100)"
SECTOR_REVIEW = "□ (요건검토) 신청서 및 제출서류 등을 확인하여 신청자격 등 검토\n* 중소기업 여부, 휴폐업·체납 여부, 신용정보, 보조금법 위반, 부적절 업종 등"
GENERAL_EXCLUSION = "□ (제외대상) 아래 지원제외 대상에 해당하는 기업\n①\n☒ 도약프로그램 지원사업을 수행 중인 기업\n②\n☒ 초격차 스타트업 1000+ 지원사업을 수행 중인 기업\n* 다년도 지원사업의 경우 ’26년이 협약기간 마지막 연차인 경우 신청 가능\n③\n☒ 신청일 기준 휴·폐업 중인 자(기업)"
REVENUE_500 = "□ (지원대상) 업력 7년 이상의 매출액 500억원 달성* 중소기업\n* 최근 3개년(’23~’25년) 표준재무제표증명원상 매출액 500억원 1회 이상 달성 기업"
REVENUE_1000 = REVENUE_500.replace("500억원", "1,000억원")
REVENUE_500_LEAD = REVENUE_LEAD.replace("100억원", "500억원")
REVENUE_1000_LEAD = REVENUE_LEAD.replace("100억원", "1,000억원")
SCOPES = ["공통", "점프업100", "점프업500", "점프업1000"]
LEADS = ["공고일 기준 업력이 7년 이상이어야 합니다."] + [lead + "이어야 합니다." for lead in (REVENUE_LEAD, REVENUE_500_LEAD, REVENUE_1000_LEAD)] + [f"{PATENT}을 부여합니다.", f"{SECTOR}이어야 합니다."]
UNCLEAR = "매출액 기준은 별표의 기업 유형별 적용 기준을 참고"


def entry(text, section="BASIC", verified=True, extra_sources=False, scope=None):
    source = dict(origin="NOTICE_BODY", attachmentName=None, sectionTitle=scope or "참여기업 조건", location=None, excerpt=text)
    sources = [source]
    if extra_sources:
        sources.append(dict(source, origin="ATTACHMENT", attachmentName="사업 공고문.pdf", location="3쪽"))
    return dict(displayStatus="VERIFIED" if verified else "EVIDENCE_ONLY", context=None, relation=None, relatedCriteria=[],
                rule=dict(section=section, label="조건", text=text, scope=scope, audience=None, details=[], exceptions=[], guidance=[], outcomes=[], sources=sources))


def guide(status="READY"):
    verified = status == "READY"
    return dict(conditions=[], rules=[], variants=[], rewriteStatus=status,
                categories=[dict(code="COMPANY_REQUIREMENTS", label="참여기업 조건", rows=[
                    dict(code="BUSINESS_AGE", label="업력", status="STATED", entries=[entry(AGE, verified=verified, extra_sources=True, scope="공통")]),
                    dict(code="REVENUE", label="매출", status="REVIEW_REQUIRED", entries=[entry(REVENUE, verified=verified, scope="점프업100"), entry(REVENUE_500, verified=verified, scope="점프업500"), entry(REVENUE_1000, verified=verified, scope="점프업1000"), entry(UNCLEAR, "EVIDENCE", False)]),
                    dict(code="IP", label="특허", status="STATED", entries=[entry(PATENT, "PREFERENCE", verified)]),
                    dict(code="SECTOR", label="유관산업", status="STATED", entries=[entry(SECTOR, verified=verified), entry(SECTOR_REVIEW, verified=verified), entry(GENERAL_EXCLUSION, "EXCLUSION", verified)]),
                    dict(code="REGION", label="소재지", status="STATED", entries=[entry("서울 소재 기업")]),
                ])])


async def assert_responsive_condition_table(region, width):
    headers = ["분야", "구분", "적용 유형", "신청 조건"] if width > 720 else ["분야", "구분", "신청 조건"]
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
        await expect(region.locator("tbody tr")).to_have_count(0)
        await expect(region.get_by_role("status")).to_be_visible()
        await expect(region.locator(".participation-guide__main")).to_have_count(0)
        await expect(region).not_to_contain_text("서울 소재 기업")
        await expect(region).to_contain_text("신청 자격과 우대사항을 확인")
        before = h.counts[1]
        ready = copy.deepcopy(data)
        ready["participationGuide"] = guide()
        h.states[1] = ready
        await h.page.clock.run_for(3100)
        await h.wait_request(1, before + 1)
        await expect(region.locator(".participation-guide__main")).to_have_text(LEADS)
        await expect(region.locator(".participation-guide__program:visible")).to_have_text(SCOPES)
        await expect(region.get_by_role("navigation", name="신청 자격 분야 바로가기").get_by_role("link")).to_have_count(4)
        await region.get_by_role("navigation").get_by_role("link", name="매출·수출", exact=True).click()
        await expect(region.locator('[data-criterion="REVENUE"]')).to_be_focused()
        await expect(region.get_by_role("status")).to_have_count(0)
        await expect(region.locator(".participation-guide__scope")).to_have_count(0)
        await expect(region.locator('[data-criterion="IP"] [data-condition-kind="preference"]')).to_have_count(1)
        await expect(region.locator('[data-criterion="IP"] td').last).to_contain_text(PATENT)
        await expect(region.locator('[data-criterion="REVENUE"] tr')).to_have_count(3)
        await expect(region).not_to_contain_text("공고에 명시 없음")
        await expect(region).not_to_contain_text("재시도")
        await assert_collapsed_notes(region)
        notes_button = region.get_by_role("button", name=re.compile(r"^업력 필수 조건 예외·추가 조건 1개 (?:펼치기|접기)$"))
        age_notes = region.locator('[data-criterion="BUSINESS_AGE"] .participation-guide__notes')
        await notes_button.focus()
        await notes_button.press("Enter")
        await expect(notes_button).to_have_attribute("aria-expanded", "true")
        await expect(notes_button).to_have_attribute("aria-label", "업력 필수 조건 예외·추가 조건 1개 접기")
        await expect(age_notes).to_be_visible()
        await expect(region.locator("blockquote")).to_have_count(0)
        evidence = region.get_by_role("button", name=re.compile(r"^업력 필수 조건 원문 근거 (?:확인|접기)$"))
        await evidence.focus()
        await evidence.press("Enter")
        await expect(evidence).to_have_attribute("aria-expanded", "true")
        await expect(evidence).to_have_attribute("aria-label", "업력 필수 조건 원문 근거 접기")
        await expect(evidence).to_have_text("원문 근거 접기")
        await expect(region.locator("blockquote")).to_have_text(AGE)
        await region.get_by_label("원문 선택", exact=True).select_option("1")
        await expect(region.get_by_role("link", name="근거 문서 열기")).to_have_attribute("href", "https://example.org/notice.pdf")
        await expect(age_notes).to_be_visible()
        await notes_button.press("Space")
        await expect(age_notes).to_be_hidden()
        await expect(evidence).to_have_attribute("aria-expanded", "true")
        await expect(region.locator("blockquote")).to_be_visible()
        await notes_button.press("Enter")
        await evidence.press("Space")
        await expect(evidence).to_have_attribute("aria-expanded", "false")
        await expect(evidence).to_have_attribute("aria-label", "업력 필수 조건 원문 근거 확인")
        await expect(evidence).to_have_text("원문 근거 확인")
        await expect(age_notes).to_be_visible()
        await notes_button.press("Space")
        await expect(notes_button).to_have_attribute("aria-label", "업력 필수 조건 예외·추가 조건 1개 펼치기")
        await expect(age_notes).to_be_hidden()
        await expect(region.locator("tbody th button")).to_have_count(0)
        await expect(region).not_to_contain_text("필수·우대 구분 확인 필요")
        await expect(region).not_to_contain_text("신청서 및 제출서류")
        await expect(region).not_to_contain_text("도약프로그램 지원사업을 수행")
        await expect(region.locator(".participation-guide__notes")).to_have_count(4)
        await set_all_notes(region, True)
        await expect(region.locator(".participation-guide__notes:visible")).to_have_count(4)
        await expect(age_notes).to_contain_text("업력 7년 미만")
        await expect(region).to_contain_text("’23~’25년")
        await expect(region).to_contain_text("별도 선발(10개사 내)")
        revenue_evidence = region.get_by_role("button", name=re.compile(r"^매출·수출 필수 조건 원문 근거 (?:확인|접기)$")).first
        await revenue_evidence.click()
        await expect(region.locator("blockquote")).to_have_text(REVENUE)
        await expect(region.locator("blockquote")).to_contain_text("별도 선발(10개사 내)")
        await revenue_evidence.click()
        for description in await region.locator(".participation-guide__description").all_text_contents():
            assert not any(symbol in description for symbol in ("□", "☒", "※", "｢", "｣", "**")), description
        await set_all_notes(region, False)
        count = h.counts[1]
        await h.page.clock.run_for(10000)
        assert h.counts[1] == count, "terminal results must stop polling"
        await expect(h.page.get_by_text(summary.PURPOSE, exact=True)).to_be_visible()
        await expect(h.page.get_by_text("검증 사업운영원", exact=True)).to_be_visible()
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await h.page.clock.run_for(300)
            await region.evaluate("""element => {
                const drawer = element.closest('.drawer');
                const toolbar = drawer.querySelector('.drawer__top');
                drawer.scrollTop += element.getBoundingClientRect().top - drawer.getBoundingClientRect().top - toolbar.offsetHeight - 12;
            }""")
            await expect(region.locator(".participation-guide__main")).to_have_text(LEADS)
            await expect(region.locator(".participation-guide__scope")).to_have_count(0)
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), width
            assert await region.locator("table").evaluate("e => e.scrollWidth <= e.clientWidth + 1"), width
            await assert_responsive_condition_table(region, width)
            assert await region.locator("tbody tr").count() == len(LEADS)
            assert await region.locator("tbody tr").evaluate_all("rows => rows.every(row => row.querySelectorAll('.participation-guide__main').length === 1)")
            await h.page.screenshot(path=str(OUT / f"verified-{width}.png"))
            if width <= 390:
                await region.evaluate("element => element.closest('.drawer').scrollTop += 850")
                await h.page.screenshot(path=str(OUT / f"verified-{width}-bottom.png"))
            await assert_collapsed_notes(region)
            await notes_button.press("Enter")
            await expect(age_notes).to_be_visible()
            await assert_responsive_condition_table(region, width)
            await evidence.click()
            await expect(region.locator("blockquote")).to_have_text(AGE)
            await expect(age_notes).to_be_visible()
            await assert_responsive_condition_table(region, width)
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), (width, "open notes and evidence")
            await evidence.click()
            await expect(age_notes).to_be_visible()
            await notes_button.press("Space")
            await expect(age_notes).to_be_hidden()
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()
    for status in ("UNAVAILABLE", None):
        h = await summary.refresh.Harness(browser).start()
        try:
            data = summary.ready()
            data["participationGuide"] = guide(status) if status else None
            h.states[1] = data
            await h.open(analysis_tab=False)
            region = h.page.get_by_role("region", name="신청 자격", exact=True)
            await expect(region.locator("tbody tr")).to_have_count(0)
            await expect(region.locator(".participation-guide__main")).to_have_count(0)
            await expect(region).to_contain_text("확인하지 못했습니다")
            await expect(region).not_to_contain_text("재시도")
            await expect(region.get_by_role("status")).to_have_count(0)
        finally:
            await h.close()


async def identical_conditions_keep_scopes(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["participationGuide"] = guide()
        fields = data["participationGuide"]["categories"][0]["rows"]
        fields[0]["entries"] = [entry("※ 단, 업력 7년 미만 추천기업도 신청대상에 포함")]
        fields[1]["entries"] = [
            entry("매출액 100억원 이상", scope="점프업100"),
            entry("매출액 100억원 이상", scope="점프업500"),
            entry("매출액 1,000억원 이상"),
        ]
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        age = region.locator('[data-criterion="BUSINESS_AGE"]')
        await expect(age.locator(".participation-guide__main")).to_have_count(0)
        await expect(age).to_have_count(0)
        revenue = region.locator('[data-criterion="REVENUE"]')
        await expect(revenue.locator(".participation-guide__scope")).to_have_count(0)
        await expect(revenue.locator(".participation-guide__main")).to_have_text(["매출액 100억원 이상이어야 합니다.", "매출액 100억원 이상이어야 합니다.", "매출액 1,000억원 이상이어야 합니다."])
        await expect(revenue).not_to_contain_text("점프업1000")
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def numbered_alternatives_and_collapsible_notes(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["participationGuide"] = guide()
        originals = [f"다음 중 하나 충족\n{first} 업력 3년 미만\n※ 공고일 기준\n{second} 매출 100억원 이상"
                     for first, second in (("①", "②"), ("1.", "2."), ("가.", "나."))]
        data["participationGuide"]["categories"][0]["rows"][0]["entries"] = [entry(text) for text in originals]
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        age = region.locator('[data-criterion="BUSINESS_AGE"]')
        await expect(age.locator(".participation-guide__main")).to_have_count(3)
        for condition in await age.locator(".participation-guide__main").all_text_contents():
            assert "조건 중 하나를 충족해야 합니다." in condition and "업력 3년 미만" in condition and "매출 100억원 이상" in condition, condition
        await expect(age.locator(".participation-guide__notes")).to_have_count(3)
        await assert_collapsed_notes(age)
        await set_all_notes(age, True)
        await expect(age.locator(".participation-guide__notes:visible")).to_have_count(3)
        await expect(age).to_contain_text("공고일 기준")
        await age.get_by_role("button", name=re.compile(r"^업력 필수 조건 원문 근거 (?:확인|접기)$")).first.click()
        await expect(age.locator("blockquote")).to_have_text(originals[0])
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def readable_clean_condition(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["participationGuide"] = guide()
        fields = data["participationGuide"]["categories"][0]["rows"]
        quote = "제조업(C10~C34)을 주된 사업으로 영위중이어야 함"
        text = "제조업(C10~C34)을 주된 사업으로 영위해야 합니다."
        condition = entry(quote)
        condition["rule"]["text"] = text
        condition["supportingQuotes"] = [quote]
        fields[3]["entries"] = [condition, entry("마. 신청서류 [표] 필수 소공인 클린제조환경조성 사업 신청서 자가진단 체크리스트")]
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        sector = region.locator('[data-criterion="SECTOR"]')
        await expect(region.locator("tbody tr")).to_have_count(6)
        await expect(sector.locator(".participation-guide__main")).to_have_text([text])
        await expect(region).not_to_contain_text("자가진단")
        await expect(region).not_to_contain_text("[표]")
        await sector.get_by_role("button", name=re.compile(r"^업종·품목·서비스 필수 조건 원문 근거 (?:확인|접기)$")).click()
        await expect(sector.locator("blockquote")).to_have_text(quote)
        await h.page.screenshot(path=str(OUT / "clean-readable.png"))
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def expanded_qualification_fields(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        items = [
            ("BUSINESS_FORM", "신청 주체", "공공기관에 해당하는 법인 또는 단체여야 합니다."),
            ("CREDIT", "신용·재무 요건", "일반기업의 경우 기업신용평가등급 B 이상이어야 합니다."),
            ("EXPERIENCE", "실적·수행 역량", "최근 1년간 공공기관 계약실적은 10억원 이하여야 합니다."),
            ("IP", "특허·인증·지정", "우수연구개발 기술개발제품 12종에 해당해야 합니다."),
            ("SECTOR", "업종·품목·서비스", "경제안보품목 및 경제안보서비스의 도입·생산·제공에 기여하는 사업자 또는 사업자단체여야 합니다."),
            ("REVENUE", "매출·수출", "전년도 직접수출액은 5,000만불 미만이어야 합니다. 단, 글로벌 강소기업 1000+ 지정기업은 5,000만불 이상도 신청 가능합니다."),
        ]
        data["participationGuide"] = dict(conditions=[], rules=[], variants=[], rewriteStatus="READY", categories=[dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[dict(code=code, label=label, status="STATED", entries=[entry(text)]) for code, label, text in items])])
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        for width in (1440, 390):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            await expect(region.locator("tbody tr")).to_have_count(6)
            for code, label, text in items:
                row = region.locator(f'[data-criterion="{code}"]')
                await expect(row.locator("th > span")).to_have_text(label)
                await expect(row.locator(".participation-guide__main")).to_have_text(text.replace("이어야 합니다. 단, ", "이어야 하지만, "))
            await expect(region.locator('[data-criterion="BUSINESS_AGE"]')).to_have_count(0)
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1")
            await region.scroll_into_view_if_needed()
            await h.page.screenshot(path=str(OUT / f"expanded-{width}.png"))
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def reported_raw_blocks_and_duplicates(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        quote = "중소기업기본법 제2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업"
        good = entry(quote, scope="공통")
        good["rule"]["text"] = quote + "이어야 합니다."
        good["supportingQuotes"] = [quote]
        compact = "공통사항□(신청자격) " + quote.replace(" ", "")
        bad = entry(compact)
        bad["supportingQuotes"] = [compact]
        raw = "신청자격 및 방법\n신청자격 :판로지원법 제2조 제2호에 따른 공공기관\n신청기간 : 상시모집\n신청방법 : 구매정보망(SMPP)을 통한 온라인(인터넷) 신청"
        data["participationGuide"] = dict(conditions=[], rules=[], variants=[], rewriteStatus="READY",
            categories=[dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[
                dict(code="BUSINESS_FORM", label="신청 주체", status="STATED", entries=[good, bad, entry(raw)]),
                dict(code="BUSINESS_AGE", label="업력", status="STATED", entries=[good, bad]),
                dict(code="CREDIT", label="신용", status="STATED", entries=[entry("신용정보 요건 충족 여부가 포함될 수 있다.")]),
            ])])
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        expected = ["중소기업기본법 제 2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업이어야 합니다.",
                    "판로지원법 제 2조 제 2호에 따른 공공기관이어야 합니다.",
                    "공고일 기준 업력이 7년 이상이어야 합니다."]
        for width in (1440, 390):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await expect(region.locator(".participation-guide__main")).to_have_text(expected)
            await expect(region.locator('[data-criterion="CREDIT"]')).to_have_count(0)
            for unwanted in ("□", "포함될 수", "신청기간", "신청방법", "필수 조건은 “"):
                await expect(region).not_to_contain_text(unwanted)
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), width
            await region.scroll_into_view_if_needed()
            await h.page.screenshot(path=str(OUT / f"reported-fixed-{width}.png"))
        buttons = region.get_by_role("button", name=re.compile(r"^신청 주체 필수 조건 원문 근거 (?:확인|접기)$"))
        await buttons.nth(1).click()
        await expect(region.locator("blockquote")).to_have_text(raw)
        await buttons.nth(1).click()
        await buttons.first.click()
        await expect(region.get_by_label("원문 선택", exact=True)).to_be_visible()
        await region.get_by_label("원문 선택", exact=True).select_option("1")
        await expect(region.locator("blockquote")).to_have_text(compact)
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def detailed_conditions_and_complete_sentences(browser):
    """Replay the reported duplicates and intact scope; no live API request is permitted."""
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        prefix = "중소기업기본법 제2조에 따라 공고일 기준 업력 7년 이상의 중소기업"
        short = prefix + "이 지원 대상입니다."
        detailed = prefix + " 중 세부 프로그램별 신청요건을 충족하는 기업이어야 합니다."
        economy = "경제안보품목 및 경제안보서비스의 원활한 도입·생산 및 제공에 기여하고자 하는 사업자 또는 사업자단체가 지원 대상입니다."
        long = "신청 분야의 업무 수행 역량을 갖춘 기업 중 " + "세부 프로그램별 신청요건과 공고에 명시된 참여 요건을 충족하고 " * 12 + "기업부설연구소를 보유한 기업이어야 합니다."
        assert len(long) > 360
        first = entry(short, scope="공통")
        second = entry(detailed, scope="공통")
        second["rule"]["sources"][0].update(origin="ATTACHMENT", attachmentName="사업 공고문.pdf", location="3쪽")
        fields = [
            dict(code="BUSINESS_FORM", label="신청 주체", status="STATED", entries=[first, second]),
            dict(code="SECTOR", label="업종·품목·서비스", status="STATED", entries=[entry(economy, scope="공급망안정화사업")]),
            dict(code="EXPERIENCE", label="실적·수행 역량", status="STATED", entries=[entry(long)]),
        ]
        data["participationGuide"] = dict(conditions=[], rules=[], variants=[], rewriteStatus="READY",
            categories=[dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=fields)])
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        expected = [detailed.replace("제2조", "제 2조"), economy, long]
        applicant = region.locator('[data-criterion="BUSINESS_FORM"]')
        evidence = applicant.get_by_role("button", name=re.compile(r"^신청 주체 필수 조건 원문 근거 (?:확인|접기)$"))
        for width in (1440, 390):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await h.page.clock.run_for(300)
            await expect(region.locator(".participation-guide__main")).to_have_text(expected)
            await expect(applicant.locator(".participation-guide__main")).to_have_count(1)
            await expect(region).not_to_contain_text("는사업 유형은")
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), width
            assert await region.locator(".participation-guide__main").evaluate_all("""elements => elements.every(e => {
                const style = getComputedStyle(e);
                return e.scrollHeight <= e.clientHeight + 1 && e.scrollWidth <= e.clientWidth + 1
                    && style.textOverflow !== 'ellipsis' && style.webkitLineClamp === 'none';
            })"""), (width, "full unclipped text")
            await evidence.click()
            selector = applicant.get_by_label("원문 선택", exact=True)
            await expect(selector.locator("option")).to_have_count(2)
            for index, original in enumerate((short, detailed)):
                await selector.select_option(str(index))
                await expect(applicant.locator("blockquote")).to_have_text(original)
            await expect(applicant.get_by_role("link", name="근거 문서 열기")).to_have_attribute("href", "https://example.org/notice.pdf")
            await evidence.click()
            await applicant.scroll_into_view_if_needed()
            await h.page.screenshot(path=str(OUT / f"detailed-complete-{width}.png"))
            await region.locator('[data-criterion="EXPERIENCE"]').scroll_into_view_if_needed()
            await h.page.screenshot(path=str(OUT / f"detailed-complete-{width}-long.png"))
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def program_scopes_and_applicant_roles(browser):
    """Program labels, actor roles and generic section headings have distinct display contracts."""
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        text = "국내 중소·중견기업이 지원 대상입니다."
        introduction = entry(text, scope="② 제조AI특화 · 신청자격")
        introduction["rule"]["audience"] = "도입기업"
        supplier = entry(text, scope="제조AI특화")
        supplier["rule"]["audience"] = "공급기업"
        ordinary = entry(text, scope="④ 대중소 상생형(일반형)")
        repeated = entry(text, scope="대중소상생형(일반형)", extra_sources=True)
        distinct = entry(text, scope="⑤ 대중소 상생형(AI형)")
        invalid = entry(text, scope="신청자격 · 지원")
        data["participationGuide"] = dict(conditions=[], rules=[], variants=[], rewriteStatus="READY", categories=[
            dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[dict(code="BUSINESS_FORM", label="신청 주체",
                status="STATED", entries=[introduction, supplier, ordinary, repeated, distinct, invalid])])])
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        expected = ["도입기업은 " + text, "공급기업은 " + text, text, text]
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await expect(region.locator(".participation-guide__main")).to_have_text(expected)
            await expect(region.locator(".participation-guide__audience:visible")).to_have_text(["도입기업", "공급기업"])
            await expect(region.get_by_text("신청 역할:", exact=True).filter(visible=True)).to_have_count(2)
            for unwanted in ("신청자격 · 지원 유형", "공통 · 지원 유형", "④", "⑤"):
                await expect(region).not_to_contain_text(unwanted)
            await assert_responsive_condition_table(region, width)
            await region.scroll_into_view_if_needed()
            await h.page.screenshot(path=str(OUT / f"program-role-{width}.png"))
        button = region.get_by_role("button", name=re.compile(r"^신청 주체 필수 조건 원문 근거 (?:확인|접기)$")).nth(2)
        await button.click()
        selector = region.get_by_label("원문 선택", exact=True)
        await expect(selector).to_be_visible()
        await selector.select_option("1")
        await expect(region.locator("blockquote")).to_have_text(text)
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def stored_smart_program_types(browser):
    """Replay the integrated smart-manufacturing notice after local source/row projection."""
    import json
    import os
    replay = os.environ.get("PARTICIPATION_SMART_REPLAY_FILE")
    if not replay:
        return
    sample = json.loads(Path(replay).read_text(encoding="utf-8"))[0]
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["participationGuide"] = sample["guide"]
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        mains = region.locator(".participation-guide__main")
        expected_programs = ["정부일반형 스마트공장", "제조AI특화 스마트공장 (자율형공장 AI트랙)",
            "대중소 상생형 스마트공장(일반형)", "대중소 상생형 스마트공장(AX선도모델)",
            "자동화공정 구축", "공급기업 역량진단", "제조 DX멘토단 활용지원", "제조데이터 상품가공 지원"]
        physical = [("제조특화 수작업 실증", "수출주력(K뷰티, K푸드, K패션)"), ("위험·유해 공정 실증", "뿌리 중소기업"),
                    ("민관협력 기술 실증", "벤처·창업기업"), ("통합실증", "검증된 피지컬AI 기술")]
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await expect(mains).to_have_count(14)
            texts = await mains.all_text_contents()
            conditions = region.locator("tbody tr")
            contexts = await region.locator(".participation-guide__program:visible").all_text_contents()
            assert len(set(await conditions.all_text_contents())) == 14
            for name in expected_programs:
                assert sum(name in context for context in contexts) == 1, (name, contexts)
            for role in ("도입기업", "공급기업"):
                matches = conditions.filter(has=h.page.locator(".participation-guide__audience:visible", has_text=role))
                await expect(matches).to_have_count(1)
                await expect(matches.locator(".participation-guide__program:visible")).to_contain_text("자율형공장")
                await expect(matches.locator(".participation-guide__program:visible")).not_to_contain_text("제조AI특화")
                assert (await matches.locator(".participation-guide__main").inner_text()).endswith("합니다.")
            for name, clause in physical:
                matches = conditions.filter(has=h.page.locator(".participation-guide__program:visible", has_text=name))
                await expect(matches).to_have_count(1)
                main = await matches.locator(".participation-guide__main").inner_text()
                assert clause in main and all(other not in main for other_name, other in physical if other_name != name), main
            for unwanted in ("신청자격 · 지원 유형", "공통 · 지원 유형", "④", "⑤"):
                assert all(unwanted not in text for text in texts), unwanted
            await assert_responsive_condition_table(region, width)
            assert await mains.evaluate_all("elements => elements.every(e => e.scrollHeight <= e.clientHeight + 1 && e.scrollWidth <= e.clientWidth + 1)"), width
            await mains.first.scroll_into_view_if_needed()
            await h.page.screenshot(path=str(OUT / f"smart-types-{width}.png"))
            await region.locator(".participation-guide__program:visible").filter(has_text="제조특화 수작업 실증").scroll_into_view_if_needed()
            await h.page.screenshot(path=str(OUT / f"smart-types-{width}-physical.png"))
        await h.page.set_viewport_size(dict(width=390, height=1000))
        program_filter = region.get_by_label("적용 유형", exact=True)
        await expect(program_filter).to_have_value("")
        before_filter = h.counts[1]
        await program_filter.select_option(label="스마트공장 구축 · 자율형공장")
        await expect(mains).to_have_count(2)
        await expect(region.locator(".participation-guide__filter-count")).to_have_text("2개 표시 / 전체 14개 조건")
        await expect(region.locator(".participation-guide__audience:visible")).to_have_text(["도입기업", "공급기업"])
        await region.evaluate("""element => {
            const drawer = element.closest('.drawer');
            drawer.scrollTop += element.getBoundingClientRect().top - drawer.getBoundingClientRect().top - drawer.querySelector('.drawer__top').offsetHeight - 12;
        }""")
        await h.page.screenshot(path=str(OUT / "smart-types-390-filtered.png"))
        await region.get_by_role("button", name="전체 보기", exact=True).click()
        await expect(mains).to_have_count(14)
        assert h.counts[1] == before_filter, "filtering must not fetch or re-extract"
        rule = region.locator("tbody tr").filter(has=h.page.locator(".participation-guide__audience:visible", has_text="도입기업"))
        await rule.get_by_role("button", name=re.compile(r"^신청 주체 필수 조건 원문 근거 (?:확인|접기)$")).click()
        selector = rule.get_by_label("원문 선택", exact=True)
        await expect(selector.locator("option")).to_have_count(2)
        originals = {source["excerpt"] for category in sample["guide"]["categories"] for row in category["rows"]
            for item in row["entries"] if item.get("displayStatus") == "VERIFIED"
            and item["rule"].get("audience") == "도입기업" for source in item["rule"]["sources"]}
        for index in range(2):
            await selector.select_option(str(index))
            assert await rule.locator("blockquote").text_content() in originals
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()
    print("PASS stored smart-manufacturing notice: 14 distinct scoped conditions, separate actors, responsive type column and original evidence, 4 widths, mocked GET only")


async def stored_notice_results(browser):
    """Replay real stored responses plus local projection; all browser APIs remain mocked."""
    import json
    import os
    replay = os.environ.get("PARTICIPATION_REPLAY_FILE")
    if not replay:
        return
    for sample in json.loads(Path(replay).read_text(encoding="utf-8")):
        h = await summary.refresh.Harness(browser).start()
        try:
            data = summary.ready()
            data["participationGuide"] = sample["guide"]
            h.states[1] = data
            await h.open(analysis_tab=False)
            region = h.page.get_by_role("region", name="신청 자격", exact=True)
            for width in (1440, 390):
                await h.page.set_viewport_size(dict(width=width, height=1000))
                await expect(region.get_by_role("table")).to_be_visible()
                await expect(region.locator(".participation-guide__scope")).to_have_count(0)
                texts = await region.locator(".participation-guide__main").all_text_contents()
                assert texts, sample["id"]
                assert all(text.endswith("다.") and "□" not in text and "사업목적" not in text for text in texts), texts
                if sample["id"] == 5:
                    for name, amount in [("점프업100", "100억원"), ("점프업500", "500억원"), ("점프업1000", "1,000억원")]:
                        assert any(name in text and amount in text for text in await region.locator("tbody tr").all_text_contents()), texts
                if sample["id"] == 1:
                    for name in ("창업과제", "일반과제", "소액과제", "딥테크과제"):
                        assert any(name in text for text in await region.locator(".participation-guide__program:visible").all_text_contents()), texts
                if sample["id"] == 7:
                    assert any("전문 기관" in text for text in texts) and not any("업력 10년" in text for text in texts)
                assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), (sample["id"], width)
                await region.scroll_into_view_if_needed()
                await region.screenshot(path=str(OUT / f"stored-{sample['id']}-{width}.png"))
            evidence = region.locator(".participation-guide__evidence-button").first
            await evidence.click()
            await expect(region.locator("blockquote")).to_be_visible()
            assert (await region.locator("blockquote").inner_text()).strip()
            await evidence.click()
        finally:
            await h.close()
    print("PASS actual stored notice projection: 7 notices, 2 widths, all program sentences, original evidence, no paid API")


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
        await region.get_by_label("적용 유형", exact=True).select_option("사업유형3")
        await expect(region.locator(".participation-guide__main")).to_have_count(3)
        await expect(region.locator(".participation-guide__program:visible")).to_have_text(["사업유형3", "공통"])
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
        await region.get_by_label("적용 유형", exact=True).select_option("사업유형3")
        await expect(region.locator(".participation-guide__main")).to_have_count(1)
        updated = copy.deepcopy(data)
        updated["participationGuide"]["rewriteStatus"] = "READY"
        updated["participationGuide"]["categories"][0]["rows"][0]["entries"] = entries[:3]
        h.states[1] = updated
        before = h.counts[1]
        await h.page.clock.run_for(3100)
        await h.wait_request(1, before + 1)
        await expect(region.get_by_label("적용 유형", exact=True)).to_have_count(0)
        await expect(region.locator(".participation-guide__main")).to_have_count(3)
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def stored_reported_quality(browser):
    """Actual reported notices: complete conditions, exceptions, context, and no paid calls."""
    import json
    import os
    replay = os.environ.get("PARTICIPATION_QUALITY_REPLAY_FILE")
    if not replay:
        return
    for sample in json.loads(Path(replay).read_text(encoding="utf-8")):
        if sample["id"] not in (4, 5, 8, 9):
            continue
        h = await summary.refresh.Harness(browser).start()
        try:
            data = summary.ready()
            data["participationGuide"] = sample["guide"]
            h.states[1] = data
            await h.open(analysis_tab=False)
            region = h.page.get_by_role("region", name="신청 자격", exact=True)
            mains = region.locator(".participation-guide__main")
            for width in (1440, 390):
                await h.page.set_viewport_size(dict(width=width, height=1100))
                await expect(mains).to_have_count(1 if sample["id"] in (5, 9) else 4)
                await assert_collapsed_notes(region)
                await assert_responsive_condition_table(region, width)
                if sample["id"] == 8:
                    await region.scroll_into_view_if_needed()
                    await h.page.screenshot(path=str(OUT / f"quality-8-{width}-collapsed.png"))
                await set_all_notes(region, True)
                texts = await mains.all_text_contents()
                assert all(text.endswith("다.") for text in texts), texts
                assert not any(term in text for text in texts for term in ("7년 년", "의중중소", "소기기업", "및 방법", "신청자격 :", "의이어야")), texts
                if sample["id"] == 8:
                    for number in ("100억원", "500억원", "1,000억원"):
                        assert any(number in text for text in texts), texts
                    assert any("7년 이상" in text and "7년 미만" in text and "마일스톤트랙 추천기업" in text for text in texts), texts
                    for scope in ("점프업100", "점프업500", "점프업1000"):
                        rule = region.locator("tbody tr").filter(has=h.page.locator(".participation-guide__program:visible", has_text=re.compile(r"^" + re.escape(scope) + r"$")))
                        notes = await rule.locator(".participation-guide__notes").inner_text()
                        assert "23" in notes and "25" in notes and "1회이상" in notes.replace(" ", ""), (scope, notes)
                        if scope == "점프업100":
                            assert "마일스톤트랙" in notes and "3년미만" in notes.replace(" ", ""), notes
                elif sample["id"] == 4:
                    await expect(region.locator(".participation-guide__program:visible")).to_have_text(["딥테크과제", "창업과제", "일반과제", "소액과제"])
                    assert any("최근 10년간" in text and "10억원" in text for text in texts), texts
                    assert any("5억원" in text and "또는" in text for text in texts), texts
                    assert not any("공공기관이 지원 대상" in text for text in texts), texts
                elif sample["id"] == 5:
                    await expect(region.locator('[data-criterion="BUSINESS_FORM"] .participation-guide__main')).to_have_count(1)
                    await expect(mains).to_contain_text("도시형소공인")
                    notes = await region.locator(".participation-guide__notes").inner_text()
                    assert "C10~C34" in notes and "10인" in notes.replace(" ", "") and "모두충족" in notes.replace(" ", ""), notes
                    assert "산재보험" in notes, notes
                    assert not any(mark in notes for mark in ("[표]", "[/표]", "⏎", "\t")), notes
                else:
                    assert any("5,000만불 미만" in text and "5,000만불 이상" in text for text in texts), texts
                assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), (sample["id"], width)
                assert await mains.evaluate_all("elements => elements.every(e => e.scrollHeight <= e.clientHeight + 1 && e.scrollWidth <= e.clientWidth + 1)"), (sample["id"], width)
                await region.scroll_into_view_if_needed()
                await h.page.screenshot(path=str(OUT / f"quality-{sample['id']}-{width}.png"))
                await assert_responsive_condition_table(region, width)
                if sample["id"] == 8:
                    jump_up = region.locator("tbody tr").filter(has=h.page.locator(".participation-guide__program:visible", has_text=re.compile(r"^점프업100$")))
                    await jump_up.scroll_into_view_if_needed()
                    await h.page.screenshot(path=str(OUT / f"quality-8-{width}-notes-open.png"))
                    await jump_up.locator(".participation-guide__evidence-button").click()
                    await expect(jump_up.locator(".participation-guide__notes")).to_be_visible()
                    await expect(jump_up.locator("blockquote")).to_be_visible()
                    await assert_responsive_condition_table(region, width)
                    await jump_up.locator(".participation-guide__actions").scroll_into_view_if_needed()
                    await h.page.screenshot(path=str(OUT / f"quality-8-{width}-both-open.png"))
                    await jump_up.locator(".participation-guide__evidence-button").click()
                    await expect(jump_up.locator(".participation-guide__notes")).to_be_visible()
                await set_all_notes(region, False)
            await region.locator(".participation-guide__evidence-button").first.click()
            await expect(region.locator("blockquote")).to_be_visible()
            assert (await region.locator("blockquote").inner_text()).strip()
            if sample["id"] == 5:
                selector = region.get_by_label("원문 선택", exact=True)
                await expect(selector.locator("option")).to_have_count(3)
                expected_originals = {source["excerpt"] for category in sample["guide"]["categories"]
                    for row in category["rows"] for item in row["entries"] if item.get("displayStatus") == "VERIFIED"
                    for source in item["rule"]["sources"]}
                displayed_originals = set()
                for index in range(3):
                    await selector.select_option(str(index))
                    displayed_originals.add(await region.locator("blockquote").text_content())
                assert displayed_originals == expected_originals
            assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
        finally:
            await h.close()
    print("PASS actual reported notices: complete sentences and exceptions, 4 procurement types, 3 jump-up revenue thresholds with dates, 3 identical small-manufacturer sources merged with all originals, collapsed notes and independent source disclosure, 2 widths")


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel="msedge", headless=True)
        try:
            await stored_reported_quality(browser)
            await selected_program_retains_common_conditions(browser)
            await disappearing_program_filter_restores_all_conditions(browser)
            await stored_notice_results(browser)
            await stored_smart_program_types(browser)
            await reported_raw_blocks_and_duplicates(browser)
            await detailed_conditions_and_complete_sentences(browser)
            await program_scopes_and_applicant_roles(browser)
            await check(browser)
            await readable_clean_condition(browser)
            await expanded_qualification_fields(browser)
            await identical_conditions_keep_scopes(browser)
            await numbered_alternatives_and_collapsible_notes(browser)
        finally:
            await browser.close()
    print("PASS seven eligibility fields, export exception, applicant/credit/experience, hidden empty rows, complete conditions with independently collapsible exceptions, explicit program contexts, original evidence, no category warning, sector filtering, preserved math, keyboard controls, 4 widths, terminal polling")


asyncio.run(main())
