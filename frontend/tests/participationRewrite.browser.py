"""Four company fields: readable conditions, preserved source evidence, responsive UI.
Only mocked GET APIs are used; no analysis or model API can be called.
"""
import asyncio
import copy
import importlib.util
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
        await expect(region.get_by_role("status")).to_have_count(0)
        await expect(region.locator(".participation-guide__scope strong")).to_have_text(SCOPES)
        await expect(region.locator('[data-criterion="IP"] td').first).to_contain_text("확인된 조건 없음")
        await expect(region.locator('[data-criterion="IP"] td').last).to_contain_text(PATENT)
        await expect(region.locator('[data-criterion="REVENUE"] td').last).to_contain_text("확인 필요")
        await expect(region).not_to_contain_text("공고에 명시 없음")
        await expect(region).not_to_contain_text("재시도")
        evidence = region.get_by_role("button", name="업력 필수 조건 근거 보기", exact=True)
        await evidence.focus()
        await evidence.press("Enter")
        await expect(evidence).to_have_attribute("aria-expanded", "true")
        await expect(region.locator("blockquote")).to_have_text(AGE)
        await region.get_by_label("원문 선택", exact=True).select_option("1")
        await expect(region.get_by_role("link", name="근거 문서 열기")).to_have_attribute("href", "https://example.org/notice.pdf")
        await evidence.press("Space")
        await expect(evidence).to_have_attribute("aria-expanded", "false")
        await expect(region.locator("tbody th button")).to_have_count(0)
        await expect(region).not_to_contain_text("필수·우대 구분 확인 필요")
        await expect(region).not_to_contain_text("신청서 및 제출서류")
        await expect(region).not_to_contain_text("도약프로그램 지원사업을 수행")
        await expect(region.locator(".participation-guide__notes")).to_have_count(0)
        await expect(region).not_to_contain_text("업력 7년 미만")
        await expect(region).not_to_contain_text("’23~’25년")
        await expect(region).not_to_contain_text("별도 선발(10개사 내)")
        revenue_evidence = region.get_by_role("button", name="매출·수출 필수 조건 근거 보기", exact=True).first
        await revenue_evidence.click()
        await expect(region.locator("blockquote")).to_have_text(REVENUE)
        await expect(region.locator("blockquote")).to_contain_text("별도 선발(10개사 내)")
        await revenue_evidence.click()
        for description in await region.locator(".participation-guide__description").all_text_contents():
            assert not any(symbol in description for symbol in ("□", "☒", "※", "｢", "｣", "**")), description
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
            await expect(region.locator(".participation-guide__scope strong")).to_have_text(SCOPES)
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), width
            assert await region.locator("table").evaluate("e => e.scrollWidth <= e.clientWidth + 1"), width
            if width <= 390:
                await expect(region.locator(".participation-guide__mobile-label").first).to_be_visible()
            await h.page.screenshot(path=str(OUT / f"verified-{width}.png"))
            if width <= 390:
                await region.evaluate("element => element.closest('.drawer').scrollTop += 850")
                await h.page.screenshot(path=str(OUT / f"verified-{width}-bottom.png"))
            await evidence.click()
            await expect(region.locator("blockquote")).to_have_text(AGE)
            assert await region.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), (width, "open evidence")
            await evidence.click()
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
        await expect(revenue.locator(".participation-guide__scope strong")).to_have_text(["점프업100", "점프업500"])
        await expect(revenue.locator(".participation-guide__main")).to_have_text(["매출액 100억원 이상이어야 합니다.", "매출액 100억원 이상이어야 합니다.", "매출액 1,000억원 이상이어야 합니다."])
        await expect(revenue).not_to_contain_text("점프업1000")
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def numbered_alternatives_survive_hidden_notes(browser):
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
        await expect(age).not_to_contain_text("공고일 기준")
        await expect(age.locator(".participation-guide__notes")).to_have_count(0)
        await age.get_by_role("button", name="업력 필수 조건 근거 보기", exact=True).first.click()
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
        await expect(region.locator("tbody tr")).to_have_count(4)
        await expect(sector.locator(".participation-guide__main")).to_have_text([text])
        await expect(region).not_to_contain_text("자가진단")
        await expect(region).not_to_contain_text("[표]")
        await sector.get_by_role("button", name="업종·품목·서비스 필수 조건 근거 보기", exact=True).click()
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
                await expect(row.locator("th")).to_have_text(label)
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
        expected = ["중소기업이어야 하며, 세부 프로그램별 신청 요건을 충족해야 합니다.",
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
        buttons = region.get_by_role("button", name="신청 주체 필수 조건 근거 보기", exact=True)
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


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel="msedge", headless=True)
        try:
            await reported_raw_blocks_and_duplicates(browser)
            await check(browser)
            await readable_clean_condition(browser)
            await expanded_qualification_fields(browser)
            await identical_conditions_keep_scopes(browser)
            await numbered_alternatives_survive_hidden_notes(browser)
        finally:
            await browser.close()
    print("PASS seven eligibility fields, export exception, applicant/credit/experience, hidden empty rows, main conditions only, explicit program scopes, original footnotes in evidence, no category warning, sector filtering, preserved math, keyboard controls, 4 widths, terminal polling")


asyncio.run(main())
