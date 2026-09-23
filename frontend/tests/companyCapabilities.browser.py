"""Verify company, legacy and empty capability cards using mocked GET APIs only."""
import asyncio
import os
from pathlib import Path
from urllib.parse import urlparse

from playwright.async_api import async_playwright, expect

BASE = os.environ.get("CAPABILITY_PREVIEW_URL", "http://127.0.0.1:4185")
OUT = Path("frontend/dist/capability-checks")
COMPANY = {
    "companyEvidenceId": "service:1",
    "confirmedFact": "회사는 제조 AI 에이전트 설계·개발 서비스를 제공합니다.",
    "strategicInterpretation": "제조 AI 실증 과업의 설계와 현장 적용에 활용합니다.",
}
LEGACY = {
    "confirmedFact": "제출서류로 참여기업의 최근 3개년 결산재무제표 제출을 요구합니다.",
    "strategicInterpretation": "재무제표 준비 여부를 확인합니다.",
}


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport={"width": 1280, "height": 900})
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        mode = "company"

        async def mock_api(route):
            assert route.request.method == "GET", "This check must never mutate server data"
            path = urlparse(route.request.url).path
            item = dict(
                detectionId=1, documentId=1, versionId=1, runId=1,
                organizationName="검증 기관", boardName="사업공고", title="제조 AI 실증 사업",
                changeType="NEW_DOCUMENT", attachmentCount=0, importance="HIGH",
                opportunityScore=80, opportunityPriority="HIGH", lastCheckedAt="2026-09-23T10:00:00",
            )
            paged = lambda content: dict(
                content=content, totalPages=1, totalElements=len(content),
                page=0, size=20, first=True, last=True,
            )
            if path == "/api/monitoring-runs":
                data = paged([dict(runId=1, requestedAt="2026-09-23T10:00:00",
                    triggerType="MANUAL", status="COMPLETED", totalSourceCount=1,
                    detectedDocumentCount=1, warningCount=0, reportTitle="보고서")])
            elif path == "/api/bookmarks/versions":
                data = []
            elif path == "/api/document-detections":
                data = paged([item])
            elif path == "/api/document-detections/1":
                matches = [COMPANY] if mode == "company" else [LEGACY] if mode == "legacy" else []
                strategy = dict(
                    decision="CONDITIONAL_GO", decisionReason="실증 파트너 확인 후 참여를 검토합니다.",
                    recommendedProject="제조 AI 에이전트 실증",
                    recommendedParticipation="제조기업과 역할을 협의해 AI 공급기업으로 참여합니다.",
                    alternativeParticipation="별도 기술 공급 기회를 검토합니다.",
                    capabilityMatches=matches, criticalGaps=[], stopCriteria=[],
                )
                preparation = dict(
                    meetingAgenda=["실증 파트너와 역할을 협의합니다."], eligibilityChecklist=[],
                    submissionDocuments=[], applicationDeadline=None, strategy=strategy,
                )
                proposal = dict(
                    sections=[], documentType="PROPOSAL_REQUEST", draftStatus="READY",
                    draftReason="제안 준비안입니다.", sourceAttachmentNames=[], templateSections=[],
                    draftSections=[], preparation=preparation, preparationSchemaVersion=12 if mode == "legacy" else 13,
                )
                analysis = dict(
                    summary="제조 AI 실증을 지원하는 공고입니다.", keyPoints=["실증 과업을 검토합니다."],
                    importance="HIGH", reason="회사 기술과 관련이 있습니다.", eligibility="REVIEW_REQUIRED",
                    favorableOrNot="NOT_APPLICABLE", proposal=proposal, opportunity=None,
                )
                data = dict(**item, originalUrl="https://example.org/notice",
                            publishedAt="2026-09-23T09:00:00", analysis=analysis, attachments=[])
            elif path.endswith("/similar-notices"):
                data = dict(currentNotice={}, similarNotices=[])
            elif path.endswith("/proposal-sources") or path.endswith("/proposal-drafts"):
                data = []
            elif path.endswith("/proposal-drafts/state"):
                data = dict(drafts=[], running=[])
            else:
                raise AssertionError(path)
            await route.fulfill(json=dict(isSuccess=True, data=data, message="OK"))

        await page.route("https://**/*", lambda route: route.abort())
        await page.route("**/api/**", mock_api)
        await page.add_init_script("sessionStorage.setItem('govinsight.accessToken', 'browser-test-placeholder')")
        for mode in ("company", "legacy", "empty"):
            await page.set_viewport_size({"width": 1280, "height": 900})
            await page.goto(BASE + "/documents")
            await page.locator(".document-row").click()
            await page.get_by_role("button", name="사업 제안", exact=True).click()
            await page.locator(".strategy-supporting-details > summary").click()
            card = page.locator(".strategy-capabilities")
            if mode == "company":
                await expect(card).to_be_visible()
                await expect(card).to_contain_text(COMPANY["confirmedFact"])
                await expect(card.get_by_text("회사 역량·사례", exact=True)).to_be_visible()
                await expect(card.get_by_text("공고 활용 방향", exact=True)).to_be_visible()
            elif mode == "legacy":
                await expect(card).not_to_contain_text(LEGACY["confirmedFact"])
                await expect(card).to_contain_text("이전 분석은 회사 역량과 공고 조건을 구분하지 않아")
            else:
                await expect(card).to_contain_text("이 공고와 연결해 제시할 회사 역량·사례가 확인되지 않았습니다.")
            await expect(card.get_by_text("보유 역량·실적", exact=True)).to_have_count(0)
            for width in (1280, 390):
                await page.set_viewport_size({"width": width, "height": 900})
                await card.scroll_into_view_if_needed()
                assert await card.evaluate("(el) => el.scrollWidth <= el.clientWidth")
                await card.screenshot(path=str(OUT / f"{mode}-{width}.png"))
        assert not errors, errors
        await browser.close()
        print("Company, legacy and empty cards passed at desktop and 390px widths.")


asyncio.run(main())
