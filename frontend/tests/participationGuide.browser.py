"""Participation guide UI using mocked GET responses; no live accounts or AI calls."""
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
refresh = summary.refresh
OUT = Path(__file__).resolve().parents[1] / "dist/participation-guide-checks"


def guide():
    def row(section, label, text, **extra):
        return dict(section=section, label=label, text=text, scope=extra.get("scope"),
                    audience=extra.get("audience"), details=extra.get("details", []),
                    exceptions=extra.get("exceptions", []), guidance=extra.get("guidance", []),
                    outcomes=extra.get("outcomes", []), reviewReason=extra.get("reviewReason"),
                    sources=extra.get("sources", [dict(origin="ATTACHMENT", attachmentName="사업 공고문.pdf",
                                  sectionTitle="신청 자격", location="3쪽", excerpt="□ 신청자격\n" + text)]))
    return dict(conditions=[], variants=[], rules=[
        row("BASIC", "업력·창업", "공고일 기준 업력 7년 이내 중소기업", details=["법인기업은 회사성립연월일을 기준으로 합니다."]),
        row("BASIC", "매출", "직전년도 매출액 10억 원 이상 50억 원 미만", scope="주관기업에 한함"),
        row("TARGETED", "과거 지원 이력", "해당 기업에는 아래 신청 유형 기준이 적용됩니다.", audience="2025년 기존 사업 참여기업",
            outcomes=[dict(variant="유형1", status="INELIGIBLE"), dict(variant="유형2", status="ELIGIBLE")]),
        row("PREFERENCE", "특허", "관련 분야 등록 특허 보유 기업은 가점 2점"),
        row("EXCLUSION", "세금 체납", "국세 또는 지방세를 체납 중인 기업",
            exceptions=["단, 접수 마감일까지 완납한 경우 신청할 수 있습니다."]),
        row("REVIEW", "신청 유형", "원문에서 같은 대상·유형의 신청 기준이 서로 다르게 확인됩니다.",
            sources=[dict(origin="NOTICE_BODY", excerpt="기존 참여기업은 유형1에 신청할 수 있습니다."),
                     dict(origin="NOTICE_BODY", excerpt="기존 참여기업은 유형1에 신청할 수 없습니다.")],
            outcomes=[dict(variant="유형1", status="ELIGIBLE"), dict(variant="유형1", status="INELIGIBLE")]),
        row("REVIEW", "참여 자격", "표에서 적용 대상별 자격 요건을 구분하지 못했습니다.", scope="주관기관 전담조직",
            sources=[dict(origin="NOTICE_BODY", excerpt="구분 | 운영 기준\n공공기관 | 전담인력 3명 이상\n민간기관 | 전담인력 5명 이상")]),
        row("REVIEW", "참여 자격", "(전문가단) 기술 실증·판로지원 전문가 30명 이상을 구축하고 창업기업 선정평가, 컨설팅, 프로그램 등 운영 지원.", reviewReason="SOURCE_CONTEXT_UNCONFIRMED"),
        row("REVIEW", "참여 자격", "전담인력 5인 이상, 팀원 4인 이상. 창업유관기관 창업지원 경력 3년 이상 또는 중소기업·창업기업 판로지원 경력 3년 이상.",
            reviewReason="SOURCE_CONTEXT_UNCONFIRMED", exceptions=["단, 별도 지정기관은 전담인력 3인 이상을 적용합니다."]),
    ])


async def fit(section, width):
    await section.page.set_viewport_size(dict(width=width, height=1300))
    for locator in (section.page.locator(".drawer--detail"), section, section.locator(".participation-guide__table").first):
        assert await locator.evaluate("el => el.scrollWidth <= el.clientWidth + 1"), width
    assert await section.locator(".participation-guide__main, .participation-guide__audience").evaluate_all(
        "els => els.every(el => el.scrollHeight <= el.clientHeight + 1 && getComputedStyle(el).textOverflow !== 'ellipsis')"
    ), width


async def verify(browser):
    h = await refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["analysis"] = None
        data["participationGuide"] = guide()
        h.states[1] = data
        h.states[2] = dict(data, detectionId=2)
        await h.open(analysis_tab=False)
        section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
        await expect(section.locator(".participation-guide__rule")).to_have_count(8)
        assert await section.get_by_role("heading", level=4).all_text_contents() == [
            "기본 신청 자격", "특정 기업에 적용되는 제한", "신청 제외 사유"]
        await expect(section.get_by_text("필수", exact=True)).to_have_count(0)
        await expect(section.locator(".participation-guide__outcome, .participation-guide__outcomes")).to_have_count(0)
        targeted = section.get_by_role("region", name="특정 기업에 적용되는 제한", exact=True)
        await expect(targeted.locator(".participation-guide__main")).to_have_text("2025년 기존 사업 참여기업은 유형2에 신청할 수 있으며, 유형1에는 신청할 수 없습니다.")
        await expect(targeted.locator(".participation-guide__audience")).to_have_count(0)
        await expect(section.locator(".participation-guide__scope").filter(has_text="주관기업에 한함")).to_be_visible()
        await expect(section.get_by_text("적용 대상이나 기준을 확정하지 못한", exact=False)).to_have_count(0)
        documents = h.page.get_by_role("heading", name="제출서류", exact=True)
        assert await section.evaluate("(el, y) => el.getBoundingClientRect().top > y", (await documents.bounding_box())["y"])
        evidence = section.get_by_role("button", name="공고일 기준 업력 7년 이내 중소기업 근거 보기", exact=True)
        exception = section.get_by_text("단, 접수 마감일까지 완납한 경우 신청할 수 있습니다.", exact=True)
        details_button = section.get_by_role("button", name="업력·창업 세부 조건", exact=True)
        exception_button = section.get_by_role("button", name="세금 체납 세부 조건·예외", exact=True)
        await expect(section.locator(".participation-guide__detail-panel:visible")).to_have_count(0)
        for width in (1440, 768, 390, 320):
            await fit(section, width)
            await expect(exception).to_be_hidden()
            await details_button.click()
            await expect(section.get_by_text("법인기업은 회사성립연월일을 기준으로 합니다.", exact=True)).to_be_visible()
            await evidence.click()
            await expect(details_button).to_have_attribute("aria-expanded", "false")
            await expect(section.locator("blockquote:visible")).to_have_text(data["participationGuide"]["rules"][0]["sources"][0]["excerpt"])
            await expect(section.get_by_role("link", name="근거 문서 열기")).to_have_attribute("href", "https://example.org/notice.pdf")
            await evidence.focus()
            await h.page.keyboard.press("Space")
            await expect(evidence).to_have_attribute("aria-expanded", "false")
            await exception_button.focus()
            await h.page.keyboard.press("Enter")
            await expect(exception).to_be_visible()
            await h.page.keyboard.press("Space")
            await expect(exception).to_be_hidden()
            OUT.mkdir(parents=True, exist_ok=True)
            await section.screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / f"participation-{width}.png"))
        await exception_button.click()
        await evidence.focus()
        await h.page.keyboard.press("Enter")
        await expect(evidence).to_have_attribute("aria-expanded", "true")
        await h.page.locator(".document-row").filter(has_text="상세 갱신 공고 2").evaluate("el => el.click()")
        await h.wait_request(2, 1)
        await expect(evidence).to_have_attribute("aria-expanded", "false")
        await expect(exception_button).to_have_attribute("aria-expanded", "false")
    finally:
        await h.close()


async def review_notice(browser):
    h = await refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["analysis"] = None
        data["participationGuide"] = guide()
        data["participationGuide"]["rules"] = [rule for rule in data["participationGuide"]["rules"] if rule["section"] == "REVIEW"]
        h.states[1] = data
        h.states[2] = dict(data, detectionId=2)
        await h.open(analysis_tab=False)
        section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
        notice = section.get_by_role("note", name="원문 확인이 필요한 조건", exact=True)
        toggle = notice.get_by_role("button", name="원문 확인이 필요한 조건 4건", exact=True)
        content = notice.locator(".participation-guide__review-content")
        await expect(section.get_by_role("table")).to_have_count(0)
        await expect(section.get_by_role("heading", level=4)).to_have_count(0)
        await expect(section.get_by_text("신청 자격 확인 필요", exact=True)).to_have_count(0)
        await expect(notice.locator(".participation-guide__review-note")).to_have_count(1)
        await expect(notice.get_by_text("표에서 적용 대상별 자격 요건을 구분하지 못했습니다.", exact=True)).to_have_count(0)
        await expect(notice.get_by_text("원문에서 같은 대상·유형의 신청 기준이 서로 다르게 확인됩니다.", exact=True)).to_have_count(0)
        await expect(notice.locator(".participation-guide__outcome")).to_have_count(0)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1300))
            await expect(toggle).to_have_attribute("aria-expanded", "false")
            await expect(content).to_be_hidden()
            assert (await notice.bounding_box())["height"] < 90
            await notice.screenshot(path=str(OUT / f"review-collapsed-{width}.png"))
            await toggle.focus()
            await h.page.keyboard.press("Enter")
            await expect(content).to_be_visible()
            await expect(notice.locator(".participation-guide__review-note")).to_be_visible()
            await expect(notice.get_by_text("주관기관 전담조직", exact=False)).to_be_visible()
            await expect(notice.locator(".participation-guide__main")).to_have_count(4)
            await expect(notice.locator(".participation-guide__review-reason")).to_have_count(4)
            assert "전문가 30명 이상" not in await content.inner_text()
            assert "공공기관 | 전담인력 3명 이상" not in await content.inner_text()
            assert "기존 참여기업은 유형1에 신청할 수 있습니다." not in await content.inner_text()
            await expect(notice.get_by_text("전문가단 구성·운영 요건", exact=True)).to_be_visible()
            await expect(notice.get_by_text("신청 시 갖춰야 하는 자격인지 선정 후 수행할 업무인지", exact=False)).to_be_visible()
            await expect(notice.get_by_text("직급별 인원수와 경력 기준의 적용 대상", exact=False)).to_be_visible()
            await notice.get_by_role("button", name="참여 자격 세부 조건·예외", exact=True).click()
            await expect(notice.get_by_text("단, 별도 지정기관은 전담인력 3인 이상을 적용합니다.", exact=True)).to_be_visible()
            await notice.get_by_role("button", name="참여 자격 세부 조건·예외", exact=True).click()
            titles = ["유형1 신청 기준", "기관 유형별 신청 자격", "전문가단 구성·운영 요건", "전담인력·경력 요건"]
            for original, title in zip(data["participationGuide"]["rules"], titles):
                item = notice.locator(".participation-guide__rule").filter(has=h.page.get_by_text(title, exact=True))
                button = item.get_by_role("button", name="근거 보기", exact=False)
                await button.click()
                for index, source in enumerate(original["sources"]):
                    if len(original["sources"]) > 1:
                        await item.get_by_role("combobox", name="원문 선택").select_option(str(index))
                    assert await item.locator("blockquote").text_content() == source["excerpt"]
                    await expect(item.locator(".participation-guide__review-reason")).to_be_visible()
                    if source["origin"] == "ATTACHMENT":
                        await expect(item.get_by_role("link", name="근거 문서 열기")).to_have_attribute("href", "https://example.org/notice.pdf")
                await button.click()
            for target in (section, notice, content):
                assert await target.evaluate("el => el.scrollWidth <= el.clientWidth + 1"), width
            await notice.screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / f"review-expanded-{width}.png"))
            await toggle.focus()
            await h.page.keyboard.press("Space")
            await expect(content).to_be_hidden()
        await toggle.click()
        await h.page.locator(".document-row").filter(has_text="상세 갱신 공고 2").evaluate("el => el.click()")
        await h.wait_request(2, 1)
        await expect(toggle).to_have_attribute("aria-expanded", "false")
    finally:
        await h.close()


async def multiple_sources(browser):
    h = await refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["analysis"] = None
        data["participationGuide"] = guide()
        original = data["participationGuide"]["rules"][0]["sources"][0]
        hwpx = dict(original, attachmentName="동일 공고의 아주 긴 제목을 가진 원문 파일.hwpx",
                    location="4쪽", excerpt=original["excerpt"].replace("기준 ", "기준\n"))
        other_clause = dict(original, sectionTitle="예외", location="6쪽",
                            excerpt="업력 10년 이내 기업도 신청할 수 있습니다.")
        body = dict(origin="NOTICE_BODY", sectionTitle="추가 안내", excerpt="공고 본문에 명시된 별도 기준입니다.")
        sources = [original, hwpx, other_clause, body]
        data["participationGuide"]["rules"][0]["sources"] = sources + [dict(original)]
        data["attachments"].append(dict(data["attachments"][0], attachmentId=3,
                                       fileName=hwpx["attachmentName"], fileExtension="hwpx",
                                       downloadUrl="https://example.org/notice.hwpx"))
        h.states[1] = data
        h.states[2] = dict(data, detectionId=2)
        await h.open(analysis_tab=False)
        section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
        rule = section.locator(".participation-guide__rule").first
        button = rule.get_by_role("button", name="근거 보기", exact=False)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1300))
            await button.click()
            selector = rule.get_by_role("combobox", name="원문 선택")
            await expect(selector).to_have_value("0")
            await expect(selector.locator("option")).to_have_count(4)
            for index, source in enumerate(sources):
                await selector.select_option(str(index))
                await expect(rule.locator(".participation-guide__source:visible")).to_have_count(1)
                await expect(rule.locator("blockquote:visible")).to_have_count(1)
                # Compare exact raw text, including the different wrapping in HWPX.
                assert await rule.locator("blockquote").text_content() == source["excerpt"]
                await expect(rule.locator("small")).to_have_text(
                    " · ".join(value for value in (source.get("sectionTitle"), source.get("location")) if value))
                link = rule.get_by_role("link", name="근거 문서 열기")
                if source["origin"] == "NOTICE_BODY":
                    await expect(link).to_have_count(0)
                else:
                    expected_url = "https://example.org/notice.hwpx" if index == 1 else "https://example.org/notice.pdf"
                    await expect(link).to_have_attribute("href", expected_url)
                await fit(section, width)
                assert await rule.locator(".participation-guide__source").evaluate(
                    "el => el.scrollWidth <= el.clientWidth + 1")
            await selector.focus()
            await h.page.keyboard.press("Home")
            await h.page.keyboard.press("ArrowDown")
            await h.page.keyboard.press("Enter")
            await expect(selector).to_have_value("1")
            assert await rule.locator("blockquote").text_content() == hwpx["excerpt"]
            await rule.screenshot(style=".drawer__top { visibility: hidden; }",
                                  path=str(OUT / f"single-evidence-{width}.png"))
            await button.click()
            await expect(rule.locator("blockquote")).to_have_count(0)
        await button.click()
        await rule.get_by_role("combobox", name="원문 선택").select_option("2")
        await h.page.locator(".document-row").filter(has_text="상세 갱신 공고 2").evaluate("el => el.click()")
        await h.wait_request(2, 1)
        await expect(button).to_have_attribute("aria-expanded", "false")
        await button.click()
        await expect(rule.get_by_role("combobox", name="원문 선택")).to_have_value("0")
    finally:
        await h.close()


async def empty_and_pending(browser):
    for pending in (False, True):
        h = await refresh.Harness(browser).start()
        try:
            data = refresh.detail(pending=pending, result=None)
            data["participationGuide"] = dict(conditions=[], rules=[], variants=[])
            h.states[1] = data
            await h.open(analysis_tab=False)
            section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
            await expect(section.get_by_text("신청 자격 확인 필요", exact=True)).to_be_visible()
            await expect(section).to_contain_text("조건이 없다는 뜻은 아니므로")
            await expect(section.get_by_role("table")).to_have_count(0)
            if pending:
                data["participationGuide"] = guide()
                await h.tick()
                await expect(section.get_by_role("table").first).to_be_visible()
        finally:
            await h.close()


async def official_sources(browser):
    for name in ("cbam", "startup-structured", "startup-legacy", "startup-combined", "startup-review", "clean-combined", "policy-loan-hwpx", "policy-loan-pdf"):
        fixture = Path(__file__).resolve().parents[2] / ("backend/build/participation-guide-checks/" + name + "-guide.json")
        data = summary.ready()
        data["analysis"] = None
        data["participationGuide"] = json.loads(fixture.read_text(encoding="utf-8"))
        data["attachments"][0]["fileName"] = "CBAM 공고.hwpx" if name == "cbam" else name + " 공고.hwpx"
        if name == "clean-combined":
            data["attachments"] = [dict(data["attachments"][0], attachmentId=index + 1,
                                        fileName="클린제조 " + label + " 공고.hwpx",
                                        downloadUrl="https://example.org/clean-" + str(index) + ".hwpx")
                                   for index, label in enumerate(("구조화", "평문"))]
        h = await refresh.Harness(browser).start()
        try:
            h.states[1] = data
            await h.open(analysis_tab=False)
            section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
            if name == "cbam":
                targeted = section.get_by_role("region", name="특정 기업에 적용되는 제한", exact=True)
                await expect(targeted.locator(".participation-guide__rule")).to_have_count(1)
                await expect(targeted.locator(".participation-guide__main")).to_have_text("2025년 ‘디지털기반 자동화 MRV 보급사업’ 수요기업은 유형2(제3자 검증)에만 신청할 수 있으며, 유형1(제품별 탄소배출량 산정 MRV 인프라 구축)에는 신청할 수 없습니다.")
                await expect(targeted.locator(".participation-guide__outcome, .participation-guide__audience")).to_have_count(0)
                await expect(targeted).not_to_contain_text("신청 대상으로 표시된")
                await expect(targeted).not_to_contain_text("기본 자격과 다른 제외 사유도")
                await expect(section.get_by_text("중소기업에 해당해야 합니다.", exact=True)).to_have_count(1)
                certificate = section.get_by_text("신청 마감일 기준으로 유효한 중소기업확인서를 제출할 수 있어야 합니다.", exact=True)
                await expect(certificate).to_be_hidden()
                details_button = section.get_by_role("button", name="기업 규모 세부 조건", exact=True)
                await details_button.click()
                await expect(certificate).to_be_visible()
                await expect(section.get_by_role("list", name="확인 방법").get_by_text("헬프데스크", exact=False)).to_be_visible()
                await details_button.click()
                tax_button = section.get_by_role("button", name="세금 체납 세부 조건·예외", exact=True)
                await tax_button.click()
                await expect(section.get_by_role("list", name="예외·유예 조건").filter(has_text="국세징수법")).to_be_visible()
                await tax_button.click()
                await targeted.get_by_role("button", name="근거 보기", exact=False).click()
                await expect(targeted.locator("blockquote:visible")).to_have_count(1)
                await expect(targeted.get_by_role("link", name="근거 문서 열기")).to_have_count(1)
                target_sources = next(rule["sources"] for rule in data["participationGuide"]["rules"] if rule["section"] == "TARGETED")
                for index, source in enumerate(target_sources):
                    await targeted.get_by_role("combobox", name="원문 선택").select_option(str(index))
                    assert await targeted.locator("blockquote").text_content() == source["excerpt"]
                await targeted.get_by_role("button", name="근거 보기", exact=False).click()
            elif name == "clean-combined":
                matching = section.locator(".participation-guide__main").filter(has_text="도시형소공인 지원에 관한 특별법")
                await expect(matching).to_have_count(1)
                item = section.locator(".participation-guide__rule").filter(has=h.page.locator(".participation-guide__main").filter(has_text="도시형소공인 지원에 관한 특별법"))
                original = next(rule for rule in data["participationGuide"]["rules"] if "도시형소공인 지원에 관한 특별법" in rule["text"])
                details = item.get_by_role("button", name="업종·분야 세부 조건", exact=True)
                evidence = item.get_by_role("button", name="근거 보기", exact=False)
                for width in (1440, 768, 390, 320):
                    await fit(section, width)
                    await expect(details).to_have_attribute("aria-expanded", "false")
                    await details.focus()
                    await h.page.keyboard.press("Enter")
                    panel = item.locator(".participation-guide__detail-panel")
                    await expect(panel).to_be_visible()
                    await expect(panel.get_by_text("신청조건 ①, ②를 모두 충족해야 합니다.", exact=True)).to_have_count(1)
                    assert await panel.locator("li").all_text_contents() == original["details"]
                    await item.screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / f"clean-merged-details-{width}.png"))
                    await evidence.click()
                    await expect(details).to_have_attribute("aria-expanded", "false")
                    selector = item.get_by_role("combobox", name="원문 선택")
                    await expect(selector.locator("option")).to_have_count(2)
                    for index, source in enumerate(original["sources"]):
                        await selector.select_option(str(index))
                        assert await item.locator("blockquote").text_content() == source["excerpt"]
                        file_index = 0 if "구조화" in source["attachmentName"] else 1
                        await expect(item.get_by_role("link", name="근거 문서 열기")).to_have_attribute("href", f"https://example.org/clean-{file_index}.hwpx")
                    await fit(section, width)
                    await evidence.click()
            elif name == "startup-review":
                notice = section.get_by_role("note", name="원문 확인이 필요한 조건", exact=True)
                await notice.get_by_role("button", name="원문 확인이 필요한 조건 3건", exact=True).click()
                titles = ["기관 유형별 신청 자격", "전문가단 구성·운영 요건", "전담인력·경력 요건"]
                assert await notice.locator(".participation-guide__main").all_text_contents() == titles
                await expect(notice.locator(".participation-guide__review-reason")).to_have_count(3)
                for original, title in zip([r for r in data["participationGuide"]["rules"] if r["section"] == "REVIEW"], titles):
                    item = notice.locator(".participation-guide__rule").filter(has=h.page.get_by_text(title, exact=True))
                    await item.get_by_role("button", name="근거 보기", exact=False).click()
                    assert await item.locator("blockquote").text_content() == original["sources"][0]["excerpt"]
                    await item.get_by_role("button", name="근거 보기", exact=False).click()
                for width in (1440, 768, 390, 320):
                    await fit(section, width)
                    await notice.screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / f"startup-review-reasons-{width}.png"))
            elif name.startswith("startup"):
                await expect(section.locator(".participation-guide__main").filter(has_text="공공기관의 신청 대상은")).to_have_count(1)
                await expect(section.locator(".participation-guide__main").filter(has_text="민간기관").filter(has_text="산학연협력기술지주회사")).to_have_count(1)
                assert "3 사업 개요" not in " ".join(await section.locator(".participation-guide__main").all_text_contents())
                await expect(section.get_by_role("rowheader", name="환수금 반환", exact=True)).to_be_visible()
                await expect(section.get_by_role("rowheader", name="임금 체불", exact=True)).to_be_visible()
                targeted = section.get_by_role("region", name="특정 기업에 적용되는 제한", exact=True)
                await expect(targeted.get_by_text("업력이 10년 이내인 기업이 대상입니다.", exact=True)).to_be_visible()
                grade = section.get_by_text("단, 일반기업의 경우 기업신용평가등급이 “B“ 이상이어야 합니다.", exact=True)
                await expect(grade).to_be_hidden()
                private_rule = section.locator(".participation-guide__rule").filter(has=h.page.locator(".participation-guide__main").filter(has_text="민간기관"))
                await private_rule.get_by_role("button", name="기관 유형 세부 조건", exact=True).click()
                await expect(grade).to_be_visible()
                await private_rule.screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / (name + "-details.png")))
                await private_rule.get_by_role("button", name="기관 유형 세부 조건", exact=True).click()
                if name == "startup-combined":
                    refund = section.get_by_role("row", name="환수금 반환", exact=False)
                    await refund.get_by_role("button", name="근거 보기", exact=False).click()
                    originals = next(rule["sources"] for rule in data["participationGuide"]["rules"] if rule["label"] == "환수금 반환")
                    assert len(originals) > 1
                    for index, source in enumerate(originals):
                        await refund.get_by_role("combobox", name="원문 선택").select_option(str(index))
                        await expect(refund.locator("blockquote:visible")).to_have_count(1)
                        assert await refund.locator("blockquote").text_content() == source["excerpt"]
                    await refund.screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / "startup-refund-single-evidence.png"))
                    await refund.get_by_role("button", name="근거 보기", exact=False).click()
            else:
                paragraphs = section.locator(".participation-guide__section .participation-guide__main")
                await expect(paragraphs.filter(has_text="투자금을 지원받고")).to_contain_text("선투자 추천서")
                await expect(paragraphs.filter(has_text="스마트기술")).to_contain_text("신사업창업사관학교 수료생")
                await expect(paragraphs.filter(has_text="희망형")).to_contain_text("협약")
            for width in (1440, 768, 390, 320):
                await fit(section, width)
                await section.screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / (name + "-" + str(width) + ".png")))
                if name == "cbam":
                    await section.get_by_role("region", name="기본 신청 자격", exact=True).screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / f"cbam-basic-{width}.png"))
                    await targeted.screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / f"cbam-targeted-{width}.png"))
                    await section.get_by_role("row", name="세금 체납", exact=False).screenshot(style=".drawer__top { visibility: hidden; }", path=str(OUT / f"cbam-tax-{width}.png"))
        finally:
            await h.close()


async def certification_boundaries(browser):
    for name in ("pdf", "hwpx", "combined"):
        fixture = Path(__file__).resolve().parents[2] / ("backend/build/participation-guide-checks/certification-" + name + "-guide.json")
        data = summary.ready()
        data["analysis"] = None
        data["title"] = "2026년 해외규격인증획득지원사업(일반지원) 3차 참여기업 모집공고"
        data["participationGuide"] = json.loads(fixture.read_text(encoding="utf-8"))
        data["attachments"] = [dict(data["attachments"][0], attachmentId=index + 1,
                                     fileName="인증 지원 공고." + ext, downloadUrl="https://example.org/certification." + ext)
                               for index, ext in enumerate(("pdf", "hwpx"))]
        h = await refresh.Harness(browser).start()
        try:
            h.states[1] = data
            await h.open(analysis_tab=False)
            section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
            basic = section.get_by_role("region", name="기본 신청 자격", exact=True)
            await expect(basic.locator(".participation-guide__rule")).to_have_count(1)
            await expect(basic.locator(".participation-guide__scope")).to_have_count(0)
            await expect(basic.locator(".participation-guide__main")).to_have_text("전년도 직접수출액 5,000만불 미만의 중소기업이 대상입니다.")
            await expect(section.locator(".participation-guide__main").filter(has_text="시험비 :")).to_have_count(0)
            rule = next(row for row in data["participationGuide"]["rules"] if row["section"] == "BASIC")
            await h.page.add_style_tag(content=".drawer__top { display: none !important; }")
            for width in (1440, 768, 390, 320):
                await fit(section, width)
                await h.page.set_viewport_size(dict(width=width, height=5000))
                toggle = basic.get_by_role("button", name="수출 대상 세부 조건·예외", exact=True)
                await toggle.click()
                await expect(basic.locator(".participation-guide__detail-panel")).to_contain_text("글로벌 강소기업 1000+")
                await expect(basic.locator(".participation-guide__detail-panel")).to_contain_text("5,000만불 이상")
                await basic.screenshot(path=str(OUT / ("certification-" + name + "-" + str(width) + ".png")))
                evidence = basic.get_by_role("button", name=rule["text"] + " 근거 보기", exact=True)
                await evidence.click()
                assert await basic.locator("blockquote").text_content() == rule["sources"][0]["excerpt"]
                if name == "combined":
                    await basic.get_by_role("combobox", name="원문 선택").select_option("1")
                    assert await basic.locator("blockquote").text_content() == rule["sources"][1]["excerpt"]
                    await basic.get_by_role("combobox", name="원문 선택").select_option("0")
                await evidence.click()
        finally:
            await h.close()

    h = await refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["participationGuide"] = guide()
        scope = "주관기업과 공동 참여기업에 적용하는 기준입니다.\n공고일 기준으로 소재지와 사업자등록 상태를 확인하며, 다음 요건을 모두 충족해야 합니다."
        data["participationGuide"]["rules"][0]["scope"] = scope
        h.states[1] = data
        await h.open(analysis_tab=False)
        note = h.page.locator(".participation-guide__scope").filter(has_text="주관기업과 공동 참여기업")
        assert await note.locator(".participation-guide__scope-content p").all_text_contents() == scope.split("\n")
        await h.page.add_style_tag(content=".drawer__top { display: none !important; }")
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=5000))
            assert await note.evaluate("el => el.scrollWidth <= el.clientWidth + 1")
            await note.screenshot(path=str(OUT / ("scope-paragraphs-" + str(width) + ".png")))
    finally:
        await h.close()


async def plain_eligibility(browser):
    fixture = Path(__file__).resolve().parents[2] / "backend/build/participation-guide-checks/bio-open-innovation-guide.json"
    data = summary.ready()
    data["analysis"] = None
    data["participationGuide"] = json.loads(fixture.read_text(encoding="utf-8"))
    data["attachments"] = [dict(data["attachments"][0], fileName="바이오 참여기업 모집공고.hwpx", downloadUrl="https://example.org/bio.hwpx")]
    rule = next(row for row in data["participationGuide"]["rules"] if row["label"] == "창업기업 자격")
    h = await refresh.Harness(browser).start()
    try:
        h.states[1] = data
        await h.open(analysis_tab=False)
        section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
        row = section.get_by_role("row", name="창업기업 자격", exact=False)
        await expect(row).to_have_count(1)
        await expect(row.locator(".participation-guide__main p")).to_have_text([
            "신청 대상은 중소기업 대표자입니다.", "모집공고일 기준, 다음 중 하나에 해당해야 합니다."])
        await expect(row.locator(".participation-guide__summary-options li")).to_have_text([
            "창업 후 7년 이내 창업기업", "창업 후 10년 이내 신산업 분야 창업·중소기업"])
        await expect(row.locator(".participation-guide__detail-panel")).to_be_hidden()
        await h.page.add_style_tag(content=".drawer__top { display: none !important; }")
        for width in (1440, 768, 390, 320):
            await fit(section, width)
            await h.page.set_viewport_size(dict(width=width, height=5000))
            await row.screenshot(path=str(OUT / f"plain-eligibility-{width}.png"))
            toggle = row.get_by_role("button", name="창업기업 자격 세부 조건", exact=True)
            await toggle.focus()
            await h.page.keyboard.press("Enter")
            await expect(row.locator(".participation-guide__detail-panel")).to_be_visible()
            await expect(row.get_by_role("list", name="세부 자격·추가 요건", exact=True)).to_contain_text("2019년 10월 2일~2026년 10월 1일")
            await expect(row.get_by_role("list", name="세부 자격·추가 요건", exact=True)).to_contain_text("개인사업자는 사업자등록증")
            await expect(row.get_by_role("list", name="적용 법령", exact=True).locator("li")).to_have_count(3)
            assert await row.evaluate("el => el.scrollWidth <= el.clientWidth + 1")
            await row.screenshot(path=str(OUT / f"plain-eligibility-details-{width}.png"))
            evidence = row.get_by_role("button", name="근거 보기", exact=False)
            await evidence.click()
            await expect(row.locator(".participation-guide__detail-panel")).to_be_hidden()
            for index, source in enumerate(rule["sources"]):
                await row.get_by_role("combobox", name="원문 선택").select_option(str(index))
                assert await row.locator("blockquote").text_content() == source["excerpt"]
            await evidence.click()
    finally:
        await h.close()


async def research_eligibility(browser):
    for extension in ("pdf", "hwpx", "combined"):
        fixture = Path(__file__).resolve().parents[2] / f"backend/build/participation-guide-checks/ax-{extension}-guide.json"
        data = summary.ready()
        data["analysis"] = None
        data["title"] = "지역거점 AX 혁신 기술개발 사업 신규지원 대상과제 공고"
        data["participationGuide"] = json.loads(fixture.read_text(encoding="utf-8"))
        data["attachments"] = [dict(data["attachments"][0], fileName=f"연구개발 사업 공고.{extension}",
                                    downloadUrl=f"https://example.org/notice.{extension}")]
        if extension == "combined":
            data["attachments"] = [dict(data["attachments"][0], fileName=f"연구개발 사업 공고.{ext}", downloadUrl=f"https://example.org/notice.{ext}") for ext in ("hwpx", "pdf")]
        h = await refresh.Harness(browser).start()
        try:
            h.states[1] = data
            await h.open(analysis_tab=False)
            section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
            await expect(section.get_by_role("region", name="기본 신청 자격", exact=True).locator(".participation-guide__rule")).to_have_count(2 if extension == "pdf" else 5)
            await expect(section.locator(".participation-guide__main")).not_to_contain_text(["집행가능", "사업.", "의무매칭"])
            await expect(section.get_by_role("row", name="주관기관 자격", exact=False)).to_contain_text("기업부설연구소")
            overview = h.page.get_by_role("region", name="사업 정보 한눈에 보기", exact=True)
            await expect(overview.get_by_role("row", name="자부담·사업비", exact=False)).to_have_count(0)
            evaluation = h.page.get_by_role("region", name="평가 기준", exact=True)
            evaluation_toggle = evaluation.locator(".participation-guide__evaluation-toggle")
            await expect(evaluation_toggle).to_have_attribute("aria-expanded", "false")
            await expect(section.get_by_text("민간부담금을 부담해야", exact=False)).to_have_count(0)
            await expect(section.get_by_role("heading", name="우대·가점", exact=True)).to_have_count(0)
            await expect(section.get_by_role("heading", name="감점 기준", exact=True)).to_have_count(0)
            await expect(section.get_by_text("평가 가점의 항목별 인정 기준", exact=True)).to_have_count(0)
            foreign = section.get_by_role("row", name="국외기관 참여", exact=False)
            await h.page.add_style_tag(content=".drawer__top { display: none !important; }")
            for width in (1440, 768, 390, 320):
                await fit(section, width)
                await h.page.set_viewport_size(dict(width=width, height=4000))
                await expect(section.locator(".participation-guide__detail-panel:visible")).to_have_count(0)
                if width in (1440, 390):
                    await section.screenshot(path=str(OUT / f"ax-{extension}-{width}.png"))
                await expect(overview.get_by_role("row", name="자부담·사업비", exact=False)).to_have_count(0)
                foreign_toggle = foreign.get_by_role("button", name="국외기관 참여 세부 조건·예외", exact=True)
                await foreign_toggle.click()
                await expect(foreign.locator(".participation-guide__detail-panel")).to_contain_text("공동으로 수행하는 경우로 한정")
                await foreign_toggle.click()
                if extension != "pdf":
                    await evaluation_toggle.focus()
                    await h.page.keyboard.press("Enter")
                    bonus = evaluation.get_by_role("region", name="우대·가점", exact=True)
                    penalty = evaluation.get_by_role("region", name="감점 기준", exact=True)
                    await expect(bonus.locator(".participation-guide__scope")).to_have_count(0)
                    await expect(bonus.locator(".participation-guide__main")).to_contain_text("최대 3점")
                    await expect(penalty.locator(".participation-guide__main")).to_contain_text("미납")
                    bonus_toggle = bonus.get_by_role("button", name="평가 가점 세부 조건", exact=True)
                    await bonus_toggle.focus()
                    await h.page.keyboard.press("Enter")
                    panel = bonus.locator(".participation-guide__detail-panel")
                    for text in ("90점 이상", "2천만원 이상", "10% 이상", "보건복지부", "식품의약품안전처", "제출하지 않으면 인정되지 않습니다"):
                        await expect(panel).to_contain_text(text)
                    await expect(panel.locator(".participation-guide__detail-clause")).to_have_count(23)
                    assert await panel.evaluate("el => el.scrollWidth <= el.clientWidth + 1")
                    if width in (1440, 390):
                        await panel.locator(".participation-guide__detail-clause").first.screenshot(path=str(OUT / f"ax-score-item-{extension}-{width}.png"))
                    await bonus_toggle.click()
                    await penalty.get_by_role("button", name="평가 감점 세부 조건", exact=True).click()
                    await expect(penalty.locator(".participation-guide__detail-panel")).to_contain_text("납부기한까지 납부하지 않은")
                    await penalty.get_by_role("button", name="평가 감점 세부 조건", exact=True).click()
                    await evaluation_toggle.click()
                else:
                    await evaluation_toggle.click()
                    await expect(evaluation.locator(".participation-guide__review-count")).to_have_text("2건")
                    await evaluation_toggle.click()
                if width in (1440, 390):
                    await overview.screenshot(path=str(OUT / f"ax-overview-{extension}-{width}.png"))
        finally:
            await h.close()



async def long_scope(browser):
    h = await refresh.Harness(browser).start()
    try:
        data = summary.ready()
        data["participationGuide"] = guide()
        scope = "\n".join(f"적용 대상 {i}: 공고일 기준 해당 지역에 소재하고 사업자등록을 완료한 기업에 적용합니다." for i in range(1, 8))
        data["participationGuide"]["rules"][0]["scope"] = scope
        h.states[1] = data
        await h.open(analysis_tab=False)
        section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
        note = section.locator(".participation-guide__scope").filter(has_text="적용 대상 1:")
        for width in (1440, 768, 390, 320):
            await fit(section, width)
            await expect(note.get_by_text("적용 대상 1:", exact=False)).to_be_hidden()
            button = note.get_by_role("button", name="적용 범위 보기", exact=True)
            await button.focus()
            await h.page.keyboard.press("Enter")
            await expect(note.get_by_text("적용 대상 7:", exact=False)).to_be_visible()
            assert await note.evaluate("el => el.scrollWidth <= el.clientWidth + 1")
            await note.get_by_role("button", name="적용 범위 접기", exact=True).click()
    finally:
        await h.close()


async def supplementary_only(browser):
    h = await refresh.Harness(browser).start()
    try:
        data = summary.ready()
        base = guide()["rules"][0]
        data["participationGuide"] = dict(conditions=[], rules=[dict(base, section="FUNDING", label="자부담·사업비", text="사업비의 20% 이상을 부담해야 합니다."),
            dict(base, section="PREFERENCE", label="특허", text="특허 보유 기업은 2점 가점을 받습니다.")])
        h.states[1] = data
        h.states[2] = dict(data, detectionId=2)
        await h.open(analysis_tab=False)
        section = h.page.get_by_role("region", name="신청 자격과 제한", exact=True)
        await expect(section.get_by_text("신청 자격 확인 필요", exact=True)).to_be_visible()
        await expect(section.get_by_role("table")).to_have_count(0)
        overview = h.page.get_by_role("region", name="사업 정보 한눈에 보기", exact=True)
        await expect(overview.get_by_role("row", name="자부담·사업비", exact=False)).to_have_count(0)
        await expect(h.page.get_by_text("사업비의 20% 이상을 부담해야 합니다.", exact=True)).to_have_count(0)
        evaluation = h.page.get_by_role("region", name="평가 기준", exact=True)
        toggle = evaluation.locator(".participation-guide__evaluation-toggle")
        await expect(toggle).to_have_attribute("aria-expanded", "false")
        await toggle.focus()
        await h.page.keyboard.press("Enter")
        await expect(evaluation.get_by_text("특허 보유 기업은 2점 가점을 받습니다.", exact=True)).to_be_visible()
        await evaluation.screenshot(path=str(OUT / "evaluation-expanded.png"))
        await h.page.locator(".document-row").filter(has_text="상세 갱신 공고 2").evaluate("el => el.click()")
        await h.wait_request(2, 1)
        await expect(toggle).to_have_attribute("aria-expanded", "false")
        await evaluation.screenshot(path=str(OUT / "evaluation-collapsed.png"))
    finally:
        await h.close()


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel=refresh.os.environ.get("BROWSER_CHANNEL", "msedge"))
        try:
            await verify(browser)
            await review_notice(browser)
            await multiple_sources(browser)
            await empty_and_pending(browser)
            await official_sources(browser)
            await certification_boundaries(browser)
            await plain_eligibility(browser)
            await research_eligibility(browser)
            await long_scope(browser)
            await supplementary_only(browser)
        finally:
            await browser.close()
    print("PASS: collapsed review notice, single shared guidance, cause-specific explanations separate from exact original evidence, review-only state, application sections, single evidence panel with all original sources, exact duplicate removal, source-specific links, keyboard selection, plain application-type sentences with explicit exclusivity and verified meanings, collapsed details and visible exception buttons, mutual exclusion of details/evidence, keyboard, notice reset, empty/pending refresh, 1440/768/390/320px, CBAM/startup/clean/policy-loan sources; clean duplicate merged with all details and both original excerpts preserved; certification PDF/HWPX heading boundaries, preserved eligibility and exceptions without cost definitions, readable scope paragraphs; plain-language eligibility with alternatives, incorporation dates and legal definitions, both original sources, keyboard and mobile layouts; AX PDF/HWPX actual applicant conditions, no project headings or spending rows, hidden cost-sharing rows and preserved foreign-participation limits; whole-notice bonus and deduction summaries, per-item evidence and limits, combined attachments and collapsed long scope with keyboard support; applicant-only eligibility, no dedicated funding display, separate collapsed evaluation and supplementary-only empty state")


if __name__ == "__main__":
    asyncio.run(main())
