import asyncio
from datetime import datetime

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
                    <ul>
                      <li>
                        <a href="javascript:void(0);" class="ico_file_hwp">
                          과기정통부 공고문.hwpx
                        </a>
                        <div class="down_btn">
                          <a href="javascript:void(0);"
                             onclick="fn_download('54751', '3', 'hwpx');">
                            다운로드
                          </a>
                        </div>
                      </li>
                    </ul>
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
    assert len(document.attachments) == 3
    assert document.attachments[0].file_name == "공고문.pdf"
    assert document.attachments[1].download_url == "https://example.go.kr/files/attachment/1"
    assert document.attachments[2].file_name == "과기정통부 공고문.hwpx"
    assert document.attachments[2].download_url == (
        "https://example.go.kr/ssm/file/fileDown.do?"
        "atchFileNo=54751&fileOrd=3&fileBtn=A"
    )


def test_extract_msit_document_content_and_published_date() -> None:
    async def collect():
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            await page.set_content(
                """
                <div class="board_view">
                  <div class="view_head">
                    <h2>과기정통부 사업공고</h2>
                    <div class="meta">
                      <dl class="tit_con">
                        <dt class="tit">작성일</dt>
                        <dd class="con">Aug 12, 2026</dd>
                      </dl>
                    </div>
                  </div>
                  <div id="cont-wrap" class="view_cont">
                    지원사업 공고 본문입니다.
                  </div>
                </div>
                """
            )
            try:
                return await PlaywrightCollector()._extract_document(
                    page,
                    "https://www.msit.go.kr/bbs/view.do?nttSeqNo=3186858",
                )
            finally:
                await browser.close()

    document = asyncio.run(collect())

    assert document.title == "과기정통부 사업공고"
    assert document.content_text == "지원사업 공고 본문입니다."
    assert document.published_at is not None
    assert document.published_at.date().isoformat() == "2026-08-12"


def test_extract_document_uses_list_date_when_detail_has_no_date() -> None:
    async def collect():
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            await page.set_content(
                """
                <div class="view_head"><h2>과기정통부 보도자료</h2></div>
                <div id="cont-wrap" class="view_cont">보도자료 본문</div>
                """
            )
            try:
                return await PlaywrightCollector()._extract_document(
                    page,
                    "https://www.msit.go.kr/bbs/view.do?nttSeqNo=1",
                    datetime(2026, 8, 14),
                )
            finally:
                await browser.close()

    document = asyncio.run(collect())

    assert document.published_at == datetime(2026, 8, 14)


def test_extract_remaining_institution_profiles() -> None:
    cases = [
        (
            "https://mcee.go.kr/home/web/board/read.do?boardId=1880370",
            """
            <div id="boardTableWrapper">
              <header>기후에너지환경부 지원사업 공고</header>
              <div id="boardViewListForRead_0">
                <dl><dt>등록일자</dt><dd>2026-07-29</dd></dl>
              </div>
              <div class="view_con">환경기업 지원사업 본문</div>
            </div>
            """,
            "기후에너지환경부 지원사업 공고",
            "환경기업 지원사업 본문",
            "2026-07-29",
        ),
        (
            "https://www.moel.go.kr/info/govsupport/govSupportSubView.do?bbs_seq=1",
            """
            <div class="b_info">
              <dl><dt>제목</dt><dd>고용노동부 지원사업 공고</dd></dl>
              <dl><dt>등록일</dt><dd>2026-03-17</dd></dl>
              <div class="b_content">고용지원사업 본문</div>
            </div>
            """,
            "고용노동부 지원사업 공고",
            "고용지원사업 본문",
            "2026-03-17",
        ),
        (
            "https://www.molit.go.kr/USR/BORD0201/m_69/DTL.jsp?idx=269284",
            """
            <div class="bd_view">
              <h4>국토교통부 사업 공고</h4>
              <ul class="bd_view_ul_info">
                <li><strong>등록일</strong><span>2026-08-14</span></li>
              </ul>
              <div class="bd_view_cont">국토교통사업 본문</div>
            </div>
            """,
            "국토교통부 사업 공고",
            "국토교통사업 본문",
            "2026-08-14",
        ),
    ]

    async def collect():
        documents = []
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            try:
                for url, html, *_expected in cases:
                    await page.set_content(html)
                    documents.append(
                        await PlaywrightCollector()._extract_document(page, url)
                    )
            finally:
                await browser.close()
        return documents

    documents = asyncio.run(collect())

    for document, (*_inputs, expected_title, expected_content, expected_date) in zip(
        documents, cases, strict=True
    ):
        assert document.title == expected_title
        assert document.content_text == expected_content
        assert document.published_at is not None
        assert document.published_at.date().isoformat() == expected_date
