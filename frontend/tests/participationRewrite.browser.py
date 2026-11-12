"""Server-owned eligibility wording, empty states and grouped condition interaction regression.
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


async def assert_responsive_condition_groups(region, width):
    await expect(region.get_by_role("table")).to_have_count(0)
    await expect(region.locator(".participation-guide__notes-button, .participation-guide__evidence-button")).to_have_count(0)
    content=region.locator(".participation-guide__main, .participation-guide__context, .participation-guide__notes:visible")
    assert await content.evaluate_all("""elements => elements.every(element => {
        const box=element.getBoundingClientRect();
        const group=element.closest('.participation-guide__group').getBoundingClientRect();
        return element.scrollHeight<=element.clientHeight+1 && element.scrollWidth<=element.clientWidth+1
            && box.left>=group.left-1 && box.right<=group.right+1;
    })"""), (width,"unclipped conditions and business badges")
    assert await region.evaluate("e=>e.scrollWidth<=e.clientWidth+1"),width


async def assert_collapsed_notes(region):
    for rule in await region.locator('.participation-guide__rule').filter(has=region.page.locator('.participation-guide__notes')).all():
        button=rule.locator('.participation-guide__condition-toggle')
        notes=rule.locator('.participation-guide__notes')
        count=await notes.locator('li').count()
        assert count>0
        await expect(button).to_have_attribute('aria-expanded','false')
        await expect(button).to_have_attribute('aria-label',re.compile(rf'세부 설명 {count}개$'))
        await expect(notes).to_be_hidden()


async def set_all_notes(region, opened):
    for rule in await region.locator('.participation-guide__rule').filter(has=region.page.locator('.participation-guide__notes')).all():
        button=rule.locator('.participation-guide__condition-toggle')
        notes=rule.locator('.participation-guide__notes')
        originals=await notes.locator('li').all_text_contents()
        if (await button.get_attribute('aria-expanded')=='true')!=opened:
            await button.click()
        await expect(button).to_have_attribute('aria-expanded',str(opened).lower())
        await expect(notes.locator('li')).to_have_text(originals)
        if opened:
            await expect(notes).to_be_visible()
        else:
            await expect(notes).to_be_hidden()


async def check(browser):
    h=await summary.refresh.Harness(browser).start()
    try:
        data=summary.ready()
        data['participationGuide']=guide('PENDING')
        h.states[1]=data
        await h.open(analysis_tab=False)
        region=h.page.get_by_role('region',name='신청 자격',exact=True)
        await expect(region.locator('.participation-guide__group')).to_have_count(0)
        await expect(region.get_by_role('status')).to_be_visible()
        await expect(region.locator('blockquote')).to_have_count(0)
        before=h.counts[1]
        ready=copy.deepcopy(data)
        ready['participationGuide']=guide()
        h.states[1]=ready
        await h.page.clock.run_for(3100)
        await h.wait_request(1,before+1)
        expected=[v['entries'][0]['rule']['text'] for v in ready['participationGuide']['categories'][0]['rows']]
        await expect(region.locator('.participation-guide__main')).to_have_text(expected)
        await expect(region.get_by_role('status')).to_have_count(0)
        await expect(region.locator('.participation-guide__condition-toggle')).to_have_count(7)
        await expect(region.locator('.participation-guide__group-heading h4')).to_have_text([
            '신청 주체','업력','매출·수출','특허·인증·지정','업종·품목·서비스','실적·수행 역량','지원 제외 대상'])
        await expect(region.locator('.participation-guide__number')).to_have_count(0)
        await expect(region.locator('[data-condition-kind="preference"]')).to_have_count(1)
        await expect(region.locator('[data-condition-kind="exclusion"]')).to_have_count(1)
        await expect(region.locator('[data-criterion] [data-condition-kind="exclusion"]')).to_have_count(0)
        await expect(region.locator('.participation-guide__groups > section').last).to_have_class(re.compile('participation-guide__exclusions'))
        await expect(region.locator('.participation-guide__unscoped:visible')).to_have_count(5)
        await expect(region.locator('.participation-guide__audience')).to_have_count(0)
        await region.get_by_role('navigation',name='신청 자격 항목 바로가기',exact=True).get_by_role('link',name='매출·수출',exact=True).click()
        await expect(region.locator('[data-criterion="REVENUE"]')).to_be_focused()
        await assert_collapsed_notes(region)
        age=region.locator('[data-criterion="BUSINESS_AGE"]')
        toggle=age.locator('.participation-guide__condition-toggle')
        await toggle.focus()
        await toggle.press('Enter')
        await expect(age.locator('.participation-guide__notes li')).to_have_text([AGE_NOTE])
        await expect(region.locator('blockquote')).to_have_count(0)
        await age.locator('.participation-guide__source-toggle').press('Enter')
        await expect(region.locator('blockquote')).to_have_text(AGE_QUOTE)
        await region.get_by_label('원문 선택',exact=True).select_option('1')
        await expect(region.get_by_role('link',name='근거 문서 열기')).to_have_attribute('href','https://example.org/notice.pdf')
        await toggle.press('Space')
        await expect(age.locator('.participation-guide__notes')).to_be_hidden()
        await expect(region.locator('blockquote')).to_have_count(0)
        await h.stopped()
        OUT.mkdir(parents=True,exist_ok=True)
        for width in (1440,768,390,320):
            await h.page.set_viewport_size(dict(width=width,height=1100))
            await assert_responsive_condition_groups(region,width)
            await expect(region.locator('.participation-guide__main')).to_have_text(expected)
            await expect(region.locator('.participation-guide__rule')).to_have_count(7)
            await region.scroll_into_view_if_needed()
            await h.page.screenshot(path=str(OUT/f'verified-{width}.png'))
            await toggle.press('Enter')
            await expect(age.locator('.participation-guide__notes')).to_be_visible()
            await expect(region.locator('blockquote')).to_have_count(0)
            await age.locator('.participation-guide__source-toggle').press('Enter')
            await expect(region.locator('blockquote')).to_have_text(AGE_QUOTE)
            await assert_responsive_condition_groups(region,width)
            await toggle.press('Space')
        await toggle.press('Enter')
        second=copy.deepcopy(ready)
        second.update(summary.refresh.row(2))
        h.states[2]=second
        await h.page.get_by_role('button',name='목록으로',exact=True).click()
        await h.open(identifier=2,analysis_tab=False)
        await expect(region.locator('blockquote')).to_have_count(0)
        await expect(region.locator('button[aria-expanded="true"]')).to_have_count(0)
        await assert_collapsed_notes(region)
        assert not h.errors and not h.unexpected,(h.errors,h.unexpected)
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
            await expect(region).to_contain_text("완료하지 못했습니다" if status == "UNAVAILABLE" else "정리된 신청 조건을 찾지 못했습니다")
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
        await expect(region.locator(".participation-guide__number")).to_have_text(["1", "2", "3"])
        await expect(region.get_by_role("navigation")).to_have_count(0)
        await expect(region.locator("img")).to_have_count(0)
        assert await h.page.evaluate("window.__injected === undefined")
        await region.locator(".participation-guide__condition-toggle").first.press("Enter")
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
        await region.locator('.participation-guide__source-toggle').press('Enter')
        await expect(region.locator("blockquote")).to_have_count(1)
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
            await assert_responsive_condition_groups(region, width)
            await expect(region).not_to_contain_text("실제 사업·과제유형")
            await expect(region).not_to_contain_text("신청역할")
            await expect(region).not_to_contain_text("적용 유형")
            await expect(region.locator(".participation-guide__unscoped:visible")).to_have_text("원문 확인 필요")
            await expect(region.locator(".participation-guide__program:visible")).to_have_text(["전체 사업 공통", "초기창업패키지 · 일반형"])
            await expect(region.locator(".participation-guide__audience:visible")).to_have_count(0)
            await expect(region.locator(".participation-guide__role:visible")).to_have_count(0)
            await expect(region.locator(".participation-guide__main")).to_have_text([value["rule"]["text"] for value in values])
            await region.scroll_into_view_if_needed()
            await region.screenshot(path=str(OUT / f"business-labels-{width}.png"))
        await region.locator(".participation-guide__condition-toggle").first.press("Enter")
        await expect(region.locator("blockquote")).to_have_text(text)
        await h.stopped()
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def business_scope_absence_and_uncertainty(browser):
    for case, scope, variants, expected in [
        ("no-division", None, [], None),
        ("cached-description", "실제 사업·과제유형(명시공통='공통')", [], None),
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
                await assert_responsive_condition_groups(region, width)
                if expected:
                    await expect(region.locator(".participation-guide__unscoped:visible")).to_have_text(expected)
                else:
                    await expect(region.locator(".participation-guide__context")).to_have_count(0)
                await expect(region.locator(".participation-guide__main")).to_have_text(text)
                await expect(region.locator(".participation-guide__program:visible")).to_have_count(0)
                await expect(region.locator(".participation-guide__filter")).to_have_count(0)
                await region.scroll_into_view_if_needed()
                await region.screenshot(path=str(OUT / f"scope-{case}-{width}.png"))
            await region.locator(".participation-guide__condition-toggle").press("Enter")
            await expect(region.locator("blockquote")).to_have_text(text)
            await h.stopped()
            assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
        finally:
            await h.close()


async def exclusions_only(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        data = summary.ready()
        value = entry("휴업 중인 기업은 지원에서 제외됩니다.", "EXCLUSION", scope="상위 구역")
        data["participationGuide"] = dict(conditions=[], rules=[], variants=[], rewriteStatus="READY",
            categories=[dict(code="COMPANY_REQUIREMENTS", label="신청 자격", rows=[
                dict(code="CREDIT", label="신용", status="STATED", entries=[value])])])
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role("region", name="신청 자격", exact=True)
        await expect(region.locator(".participation-guide__group-heading h4")).to_have_text(["지원 제외 대상"])
        await expect(region.locator("[data-criterion]")).to_have_count(0)
        await expect(region.locator(".participation-guide__context")).to_have_count(0)
        await expect(region.get_by_text("정리된 신청 조건을 찾지 못했습니다.")).to_have_count(0)
        await region.locator(".participation-guide__condition-toggle").press("Enter")
        await expect(region.locator("blockquote")).to_have_text(value["rule"]["text"])
        for width in (900, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await assert_responsive_condition_groups(region, width)
        await h.stopped()
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def parent_explanations(browser):
    h = await summary.refresh.Harness(browser).start()
    try:
        main = "공급망안정품목 또는 전략물자를 생산하는 중소·중견기업이 신청할 수 있습니다."
        explanations = ["공급망안정품목은 소부장특별법에 따라 선정된 품목입니다.",
                        "전략물자는 대외무역법에 따라 지정·고시되는 품목입니다.",
                        "공급망안정품목 해당 여부는 경제안보품목위원회의 확인서로 확인합니다."]
        parent = entry(main)
        parent['rule']['details'] = explanations
        parent['rule']['sources'][0]['excerpt'] = main + "\n" + "\n".join(explanations)
        data = summary.ready()
        data['participationGuide'] = guide()
        data['participationGuide']['categories'][0]['rows'] = [
            dict(code='SECTOR', label='업종', status='STATED', entries=[parent]),
            dict(code='BUSINESS_AGE', label='업력', status='STATED', entries=[entry(AGE)]),
            dict(code='CREDIT', label='신용', status='STATED', entries=[entry('국세 체납기업은 지원에서 제외됩니다.', 'EXCLUSION')])]
        h.states[1] = data
        await h.open(analysis_tab=False)
        region = h.page.get_by_role('region', name='신청 자격', exact=True)
        await expect(region.locator('.participation-guide__main')).to_have_count(3)
        await expect(region.locator('[data-criterion="IP"], [data-criterion="EXPERIENCE"]')).to_have_count(0)
        rule = region.locator('[data-criterion="SECTOR"] .participation-guide__rule')
        await expect(rule.locator('.participation-guide__group-count')).to_have_count(0)
        await expect(rule.locator('.participation-guide__notes')).to_be_hidden()
        button = rule.locator('button.participation-guide__condition-toggle')
        await button.press('Enter')
        await expect(rule.locator('.participation-guide__notes li')).to_have_text(explanations)
        await expect(rule.locator('.participation-guide__notes')).to_be_visible()
        await expect(rule.locator('blockquote')).to_have_count(0)
        evidence_toggle = rule.get_by_role('button', name='원문 근거 펼치기', exact=True)
        await expect(evidence_toggle).to_have_attribute('aria-expanded', 'false')
        assert await rule.evaluate("""element => {
            const notes = element.querySelector('.participation-guide__notes');
            const evidence = element.querySelector('.participation-guide__evidence-panel');
            return notes.parentElement === evidence.parentElement
                && notes.getBoundingClientRect().bottom < evidence.getBoundingClientRect().top
                && getComputedStyle(notes).backgroundColor !== getComputedStyle(evidence).backgroundColor;
        }"""), 'explanations and sources have distinct sibling surfaces with whitespace'
        await evidence_toggle.press('Space')
        await expect(rule.locator('blockquote')).to_contain_text(explanations[0])
        await rule.locator('.participation-guide__source-toggle').press('Enter')
        await expect(rule.locator('blockquote')).to_have_count(0)
        await expect(rule.locator('.participation-guide__notes')).to_be_visible()
        await expect(region.locator('.participation-guide__exclusions .participation-guide__main')).to_have_count(1)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await assert_responsive_condition_groups(region, width)
        await button.press('Space')
        await expect(rule.locator('.participation-guide__notes')).to_be_hidden()
        assert not h.errors and not h.unexpected, (h.errors, h.unexpected)
    finally:
        await h.close()


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel="msedge", headless=True)
        try:
            await parent_explanations(browser)
            await check(browser)
            await exclusions_only(browser)
            await cached_business_labels(browser)
            await business_scope_absence_and_uncertainty(browser)
            await source_only_states(browser)
            await server_text_and_exact_duplicates(browser)
            await selected_program_retains_common_conditions(browser)
            await disappearing_program_filter_restores_all_conditions(browser)
        finally:
            await browser.close()
    print("PASS seven server-owned fields; row evidence retained with no standalone card; empty/failed/pending states; exact duplicate citations; escaped text; separate explanation and evidence disclosures; grouped numbering; no empty scope badge; keyboard; four widths; filters without API calls; terminal polling stopped.")


asyncio.run(main())
