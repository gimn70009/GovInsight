import asyncio
from datetime import datetime
from html import escape
from urllib.parse import parse_qs, urlparse

from playwright.async_api import async_playwright

from app.domains.monitoring.collectors.playwright_collector import PlaywrightCollector
from app.domains.monitoring.schemas.request import MonitoringSourceRequest

LIST_URL = "https://www.mss.go.kr/site/smba/ex/bbs/List.do?cbIdx=310"


def _list_row(board: int, post: int) -> str:
    return f"""
    <tr onclick="doBbsFView('{board}','{post}','16010100','{post}');">
      <td>1</td>
      <td class="subject"><a href="#view">지원사업 모집</a>
        <dl><dt>신청기간</dt><dd>2026.11.01 ~ 2026.11.30</dd></dl>
      </td>
      <td class="attached-files"><a href="#">첨부파일</a></td>
      <td>2026.10.08</td><td>10</td>
      <td class="mobile"><a href="#">지원사업 모집</a></td>
    </tr>
    """


def _detail(post: str) -> str:
    body = escape(
        '<p>기업 &amp; 기관 지원</p><p>신청 안내<br>자세한 내용은 첨부 참조</p>'
        '<table><tr><td>지원대상</td><td>중소기업</td></tr></table>'
        '<script>window.invalid = true;</script><style>.hidden {display:none}</style>'
    )
    published = "<th>등록일</th><td>2026.10.07</td>" if post == "1001" else ""
    download = f"/common/board/Download.do?bcIdx={post}&amp;cbIdx=310&amp;streFileNm=notice"
    return f"""
    <h1>중소벤처기업부</h1>
    <main><h2>사업공고</h2>
      <div class="board_view">
        <h4 id="sj">지원사업 {post} 모집 공고</h4>
        <table><tbody>
          <tr class="web"><th>공고번호</th><td>제2026-1호</td>
            <th>신청기간</th><td>2026.11.01 ~ 2026.11.30</td>{published}</tr>
          <tr><td class="contents_box"><div class="view_contents"><div class="txt-area">
            <textarea id="editContents" style="display:none">{body}</textarea>
            <iframe src="/cmm/contents.jsp"></iframe>
          </div></div></td></tr>
          <tr><th>첨부파일</th><td class="file_list"><ul>
            <li><div class="info"><span class="name">사업공고.hwpx <em>[148.01 KB]</em></span></div>
              <div class="link">
                <a href="javascript:void(0);" onclick="openpage('1001','310','notice.hwpx');">
                  바로보기</a>
                <a href="{download}.hwpx">
                  내려받기</a>
                <a href="{download}.hwpx">
                  내려받기</a>
              </div>
            </li>
            <li><div class="info"><span class="name">사업공고.pdf <em>[600 KB]</em></span></div>
              <a href="{download}.pdf">
                내려받기</a>
            </li>
          </ul></td></tr>
        </tbody></table>
        <ul class="prev_next_view"><li><a href="#">다른 게시글 제목</a></li></ul>
      </div>
    </main><footer>누리집 이용안내</footer>
    """


def test_mss_collection_reads_clickable_rows_html_body_and_named_attachments() -> None:
    async def collect():
        detail_requests = []

        async def route_request(route):
            parsed = urlparse(route.request.url)
            if parsed.path.endswith("/List.do"):
                rows = "".join(_list_row(board, post) for board, post in [
                    (86, 1000), (310, 1001), (310, 1001), (310, 1002), (310, 1003),
                ])
                await route.fulfill(
                    body=f"<table><tbody>{rows}</tbody></table>",
                    content_type="text/html; charset=utf-8",
                )
            elif parsed.path.endswith("/View.do"):
                post = parse_qs(parsed.query)["bcIdx"][0]
                detail_requests.append(post)
                await route.fulfill(body=_detail(post), content_type="text/html; charset=utf-8")
            else:
                # iframe가 로드되지 않아도 원본 HTML에서 본문을 수집해야 한다.
                await route.abort()

        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            context = await browser.new_context()
            await context.route("**/*", route_request)
            try:
                result = await PlaywrightCollector()._collect_source(
                    context,
                    MonitoringSourceRequest(
                        source_id=7,
                        organization_name="중소벤처기업부",
                        board_name="사업공고",
                        list_url=LIST_URL,
                        url_include_pattern="/site/smba/ex/bbs/View.do",
                        detail_fetch_count=2,
                    ),
                )
                return result, detail_requests
            finally:
                await browser.close()

    result, requests = asyncio.run(collect())
    assert result.error_message is None
    assert requests == ["1001", "1002"]
    assert [doc.external_document_id for doc in result.documents] == ["1001", "1002"]
    assert result.documents[0].published_at == datetime(2026, 10, 7)
    assert result.documents[1].published_at == datetime(2026, 10, 8)
    for document in result.documents:
        post = document.external_document_id
        assert document.title == f"지원사업 {post} 모집 공고"
        assert document.original_url.endswith(f"cbIdx=310&bcIdx={post}&parentSeq={post}")
        assert document.content_text == (
            "기업 & 기관 지원 신청 안내 자세한 내용은 첨부 참조 지원대상 중소기업"
        )
        assert [a.file_name for a in document.attachments] == ["사업공고.hwpx", "사업공고.pdf"]
        assert [a.download_url for a in document.attachments] == [
            f"https://www.mss.go.kr/common/board/Download.do?bcIdx={post}"
            f"&cbIdx=310&streFileNm=notice.{extension}"
            for extension in ("hwpx", "pdf")
        ]
