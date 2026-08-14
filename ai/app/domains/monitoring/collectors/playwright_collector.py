import re
from datetime import datetime
from urllib.parse import urljoin

from playwright.async_api import Browser, Error, Page, async_playwright

from app.domains.monitoring.collectors.site_profiles import (
    DEFAULT_PROFILE,
    get_site_profile,
    resolve_download_url,
    resolve_link_target,
)
from app.domains.monitoring.collectors.url_filter import (
    extract_external_document_id,
    select_document_urls,
)
from app.domains.monitoring.schemas.collected_document import (
    CollectedAttachment,
    CollectedDocument,
    SourceCollectionResult,
)
from app.domains.monitoring.schemas.request import MonitoringSourceRequest

PAGE_TIMEOUT_MS = 10_000
TITLE_SELECTORS = ("h1", ".board-view-title", ".view-title", ".title")
CONTENT_SELECTORS = ("article", "main", ".board-view-content", ".view-content", ".content")
DATE_SELECTORS = ("time", "[datetime]", ".date", ".write-date", ".view-date")
ATTACHMENT_EXTENSIONS = ("pdf", "hwp", "hwpx", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip")
DATE_PATTERN = re.compile(r"(20\d{2})[.\-/년]\s*(\d{1,2})[.\-/월]\s*(\d{1,2})")

# Playwright Chromium을 사용해 여러 모니터링 소스를 순서대로 수집
class PlaywrightCollector:

    # 브라우저를 실행하고 전달받은 모든 소스를 순서대로 처리
    async def collect_sources(
        self,
        sources: list[MonitoringSourceRequest],
    ) -> list[SourceCollectionResult]:
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            try:
                return [await self._collect_source(browser, source) for source in sources]
            finally:
                await browser.close()

    # 기관 게시판 하나의 목록에서 상세 URL을 찾고 게시글을 수집
    # 실패 시 전체 작업을 중단하지 않고 소스별 실패 결과를 반환
    async def _collect_source(
        self,
        browser: Browser,
        source: MonitoringSourceRequest,
    ) -> SourceCollectionResult:
        page = await browser.new_page()
        page.set_default_timeout(PAGE_TIMEOUT_MS)
        try:
            await page.goto(source.list_url, wait_until="domcontentloaded", timeout=PAGE_TIMEOUT_MS)
            links = await page.locator("a[href], a[onclick]").evaluate_all(
                """elements => elements.map(element => ({
                    href: element.getAttribute('href'),
                    onclick: element.getAttribute('onclick'),
                    rowText: (
                        element.closest('tr, .toggle')?.textContent || ''
                    ).trim()
                }))"""
            )
            hrefs = []
            list_dates: dict[str, datetime] = {}
            for link in links:
                target = resolve_link_target(
                    link["href"],
                    link["onclick"],
                    source.list_url,
                )
                if target:
                    target = urljoin(source.list_url, target)
                hrefs.append(target)
                row_date = _parse_datetime(link["rowText"])
                if target and row_date:
                    list_dates[target] = row_date
            document_urls = select_document_urls(
                hrefs,
                source.list_url,
                source.url_include_pattern,
                source.detail_fetch_count,
            )
            documents = [
                await self._collect_document(page, url, list_dates.get(url))
                for url in document_urls
            ]
            return SourceCollectionResult(source_id=source.source_id, documents=documents)
        except (Error, OSError, ValueError) as exception:
            return SourceCollectionResult(
                source_id=source.source_id,
                error_message=str(exception)[:2000],
            )
        finally:
            await page.close()

    async def _collect_document(
        self,
        page: Page,
        url: str,
        fallback_published_at: datetime | None = None,
    ) -> CollectedDocument:
        await page.goto(url, wait_until="domcontentloaded", timeout=PAGE_TIMEOUT_MS)
        return await self._extract_document(page, url, fallback_published_at)

    async def _extract_document(
        self,
        page: Page,
        url: str,
        fallback_published_at: datetime | None = None,
    ) -> CollectedDocument:
        profile = get_site_profile(url)
        title = await _first_text(page, profile.title_selectors)
        if not title and profile != DEFAULT_PROFILE:
            title = await _first_text(page, TITLE_SELECTORS)
        if not title:
            title = (await page.title()).strip()
        if not title:
            raise ValueError(f"게시글 제목을 찾을 수 없습니다: {url}")

        return CollectedDocument(
            original_url=url,
            external_document_id=extract_external_document_id(url),
            title=title,
            content_text=await _first_text(page, profile.content_selectors),
            published_at=(
                _parse_datetime(await _first_date_value(page, profile.date_selectors))
                or fallback_published_at
            ),
            attachments=await _collect_attachments(page, url),
        )


async def _first_text(page: Page, selectors: tuple[str, ...]) -> str | None:
    for selector in selectors:
        locator = page.locator(selector).first
        if await locator.count() == 0:
            continue
        normalized = " ".join((await locator.inner_text()).split())
        if normalized:
            return normalized
    return None


async def _first_date_value(
    page: Page,
    selectors: tuple[str, ...] = DATE_SELECTORS,
) -> str | None:
    for selector in selectors:
        locator = page.locator(selector).first
        if await locator.count() == 0:
            continue
        value = await locator.get_attribute("datetime") or await locator.inner_text()
        if value and value.strip():
            return value.strip()
    return None


def _parse_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        pass

    match = DATE_PATTERN.search(value)
    if match:
        return datetime(*(int(part) for part in match.groups()))

    for date_format in ("%b %d, %Y", "%B %d, %Y"):
        try:
            return datetime.strptime(value, date_format)
        except ValueError:
            continue
    return None


async def _collect_attachments(page: Page, page_url: str) -> list[CollectedAttachment]:
    links = await page.locator("a[href]").evaluate_all(
        """elements => elements.map(element => ({
            href: element.getAttribute('href'),
            text: (element.textContent || '').trim(),
            download: element.getAttribute('download') || '',
            onclick: element.getAttribute('onclick'),
            relatedFileName: (
                element.closest('li')?.querySelector('a[class*="ico_file_"]')?.textContent || ''
            ).trim()
        }))"""
    )
    attachments: list[CollectedAttachment] = []
    seen: set[str] = set()
    extensions = "|".join(ATTACHMENT_EXTENSIONS)
    extension_pattern = re.compile(rf"\.({extensions})(?:$|[?#])", re.IGNORECASE)
    file_name_pattern = re.compile(rf"\.({extensions})\b", re.IGNORECASE)

    for link in links:
        raw_href = link["href"]
        text = link["text"]
        related_file_name = link["relatedFileName"]
        url = resolve_download_url(raw_href, page_url, link["onclick"])
        is_file = bool(
            link["download"]
            or extension_pattern.search(raw_href or "")
            or file_name_pattern.search(text)
            or file_name_pattern.search(related_file_name)
        )
        if not url or url in seen or not is_file:
            continue
        file_name = (
            link["download"]
            or related_file_name
            or text
            or url.rsplit("/", maxsplit=1)[-1]
        )
        attachments.append(CollectedAttachment(file_name=file_name[:500], download_url=url))
        seen.add(url)
    return attachments
