"""Verify review sections stay hidden while confirmed documents and meetings remain."""
import asyncio
import os
from pathlib import Path
from urllib.parse import urlparse

from playwright.async_api import async_playwright, expect

BASE = os.environ.get("REVIEW_PREVIEW_URL", "http://127.0.0.1:4188")
OUT = Path(__file__).resolve().parents[1] / "dist" / "review-checks"
COMPANY = {
    "companyEvidenceId": "service:1",
    "confirmedFact": "회사는 제조 AI 에이전트 설계·개발 서비스를 제공합니다.",
    "strategicInterpretation": "제조 AI 실증 과업의 설계와 현장 적용에 활용합니다.",
}
LEGACY = {
    "confirmedFact": "제출서류로 참여기업의 최근 3개년 결산재무제표 제출을 요구합니다.",
    "strategicInterpretation": "재무제표 준비 여부를 확인합니다.",
}


ARCHIVE = "붙임 01. 연구개발계획서 등 관련양식.zip"
TITLES = [
    "산업기술혁신사업 연구개발계획서(통합형(세부))_으뜸기업",
    "산업기술혁신사업 연구개발계획서(통합형 총괄)_으뜸기업",
    "개인정보 및 과세정보 제공 활용 동의서, 연구윤리 청렴 및 보안서약서",
    "신청 자격 적정성 확인서_으뜸기업",
    "연구개발기관 대표의 참여의사 확인서(국내기관용)",
    "연구수행총량 및 연구자의 동시수행 과제수 준수 확약서",
    "수요기업 확약서_으뜸기업(붙임6, 비필수로 표기된 파일)",
    "평가결과 이의신청서",
]
FILES = ["1_1. (필수)" + TITLES[0], "1_2. (필수)" + TITLES[1],
    "3. (필수)" + TITLES[2], "5. (필수)" + TITLES[3], "첨부1. (필수)" + TITLES[4],
    "첨부3. (필수)연구수행총량(산업부) 및 연구자의 동시수행 과제수(전부처) 준수 확약서",
    "첨부4. (비필수)(국외기관용)산업기술혁신사업 참여의사 확인서"]
def note(title, source):
    return f"제출 여부 확인: {title} — 별도 제출 의무가 확인되지 않았습니다. {source}에서 제출 대상·조건과 상위 서류에 포함할 내용인지 확인합니다."
NOTES = [note(title, ARCHIVE if index < 7 else "붙임 02. 평가결과 이의신청서 양식.zip")
    for index, title in enumerate(TITLES)] + [note(title, title + ".hwpx") for title in FILES]
DECISIONS = ["참여 방식 결정: 주관연구개발기관 또는 공동연구개발기관의 역할을 결정합니다.",
    "수요기업 확보 및 확약서 제출 가능성을 확인합니다.", "기업부설연구소 보유 여부를 확인합니다.",
    "재무증빙 확보 가능성을 검토합니다.", "기관부담연구개발비의 현금비율을 논의합니다.",
    "IRIS 접수 담당자와 인증 절차를 정합니다.", "제출서류 대조 담당자를 정합니다.",
    "R&D자율성트랙 신청 가능성을 검토합니다."]


FINANCIAL = dict(
    title="결산재무제표", detail="최근 3개년 결산 재무제표(2023~2025) 원본 또는 사본(원본 대조필)",
    nextAction="결산재무제표를 준비합니다. 최근 3개년 결산 재무제표(2023~2025) 원본 또는 사본(원본 대조필). 기업이 2개 이상인 경우 압축 파일로 제출",
    stage="APPLICATION", requirementLevel="MANDATORY", appliesTo="참여기업",
    source=dict(origin="ATTACHMENT", attachmentName="붙임2 신청안내.hwpx", sectionTitle="제출서류",
                excerpt="최근 3개년 결산 재무제표(2023~2025) 원본 또는 사본(원본 대조필)"),
)
FINANCIAL_NOTES = [
    "결산재무제표(2023~2025) 원본 또는 원본대조필 사본 — 원본 대조필 절차와 담당자를 지정하여 확인합니다.",
    "중소기업확인서 — 보유 여부 및 유효기간을 확인합니다.",
]


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport={"width": 1280, "height": 900})
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        mode = "legacy"

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
                    meetingAgenda=DECISIONS + (["\n".join(NOTES[:4])] + NOTES[4:] if mode == "legacy" else []),
                    submissionReviewNotes=([] if mode == "legacy" else
                        FINANCIAL_NOTES if mode == "confirmed" else FINANCIAL_NOTES[:1] if mode == "covered" else NOTES),
                    eligibilityChecklist=[],
                    submissionDocuments=[FINANCIAL] if mode in ("confirmed", "covered") else [], applicationDeadline=None, strategy=strategy,
                )
                proposal = dict(
                    sections=[], documentType="PROPOSAL_REQUEST", draftStatus="READY",
                    draftReason="제안 준비안입니다.", sourceAttachmentNames=[], templateSections=[],
                    draftSections=[], preparation=preparation, preparationSchemaVersion=17 if mode == "legacy" else 18,
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
        for mode in ("legacy", "new", "confirmed", "covered"):
            await page.set_viewport_size({"width": 1280, "height": 960})
            await page.goto(BASE + "/documents")
            await page.locator(".document-row").click()
            await page.get_by_role("button", name="사업 제안", exact=True).click()
            await page.locator("#proposal-agenda > summary").click()
            await page.locator("#proposal-documents > summary").click()
            await expect(page.locator(".meeting-agenda > li")).to_have_count(8)
            await expect(page.locator(".meeting-agenda")).not_to_contain_text("제출 여부 확인:")
            await expect(page.locator(".submission-reviews")).to_have_count(0)
            documents = page.locator("#proposal-documents")
            await expect(documents).not_to_contain_text("원문 확인 사항")
            await expect(documents).not_to_contain_text("중소기업확인서")
            await expect(documents.locator(":scope > summary")).not_to_contain_text("원문 확인")
            count = 1 if mode in ("confirmed", "covered") else 0
            await expect(documents.locator(":scope > summary em")).to_have_text(f"{count}건")
            if count:
                checklist = documents.locator(":scope > .preparation-checklist")
                await expect(checklist).to_contain_text("결산재무제표")
                await expect(checklist).to_contain_text("2023~2025")
                await expect(checklist).to_contain_text("원본 대조필")
                await expect(checklist).to_contain_text("원문 근거 보기")
            for width in (1280, 390):
                await page.set_viewport_size({"width": width, "height": 960})
                await documents.scroll_into_view_if_needed()
                assert await page.evaluate("document.documentElement.scrollWidth <= innerWidth")
                await page.screenshot(path=str(OUT / f"{mode}-{width}.png"))
        assert not errors, errors
        await browser.close()
        print("All 4 data modes: no review section or review count, 8 meeting decisions preserved; confirmed financial documents, conditions and source toggles retained at 1280px and 390px.")


asyncio.run(main())
