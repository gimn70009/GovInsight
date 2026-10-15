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
        await expect(section.locator(".participation-guide__rule")).to_have_count(9)
        assert await section.locator("h5").all_text_contents() == [
            "기본 신청 자격", "특정 기업에 적용되는 제한", "신청 제외 사유", "우대·가점"]
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
        await expect(section.locator("h5")).to_have_count(0)
        await expect(section.get_by_text("참여 조건 확인 필요", exact=True)).to_have_count(0)
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
            await expect(section.get_by_text("참여 조건 확인 필요", exact=True)).to_be_visible()
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


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel=refresh.os.environ.get("BROWSER_CHANNEL", "msedge"))
        try:
            await verify(browser)
            await review_notice(browser)
            await multiple_sources(browser)
            await empty_and_pending(browser)
            await official_sources(browser)
        finally:
            await browser.close()
    print("PASS: collapsed review notice, single shared guidance, cause-specific explanations separate from exact original evidence, review-only state, application sections, single evidence panel with all original sources, exact duplicate removal, source-specific links, keyboard selection, plain application-type sentences with explicit exclusivity and verified meanings, collapsed details and visible exception buttons, mutual exclusion of details/evidence, keyboard, notice reset, empty/pending refresh, 1440/768/390/320px, CBAM/startup/clean/policy-loan sources; clean duplicate merged with all details and both original excerpts preserved")


if __name__ == "__main__":
    asyncio.run(main())
