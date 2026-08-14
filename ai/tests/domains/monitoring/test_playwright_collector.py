import asyncio

from playwright.async_api import async_playwright

from app.domains.monitoring.collectors.playwright_collector import PlaywrightCollector


def test_collect_document_extracts_content_and_attachment() -> None:
    async def collect():
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            await page.set_content(
                """
                <html>
                  <head><title>대체 제목</title></head>
                  <body>
                    <h1>  공공기관   공고  </h1>
                    <time datetime="2026-08-14">2026. 8. 14.</time>
                    <article>첫 번째 문단\n두 번째 문단</article>
                    <a href="https://example.go.kr/files/notice.pdf">공고문.pdf</a>
                    <a href="javascript:location.href='/files/attachment/1'">
                      첨부자료.hwpx (20 KB)
                    </a>
                  </body>
                </html>
                """
            )
            try:
                return await PlaywrightCollector()._extract_document(
                    page,
                    "https://example.go.kr/notices/123",
                )
            finally:
                await browser.close()

    document = asyncio.run(collect())

    assert document.title == "공공기관 공고"
    assert document.content_text == "첫 번째 문단 두 번째 문단"
    assert document.published_at is not None
    assert document.published_at.date().isoformat() == "2026-08-14"
    assert document.external_document_id == "123"
    assert len(document.attachments) == 2
    assert document.attachments[0].file_name == "공고문.pdf"
    assert document.attachments[1].download_url == "https://example.go.kr/files/attachment/1"
