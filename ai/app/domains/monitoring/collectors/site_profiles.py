import re
from dataclasses import dataclass
from urllib.parse import parse_qs, urlencode, urljoin, urlparse


# 기관별 상세 페이지에서 제목, 본문, 게시일을 찾는 CSS 선택자 설정
@dataclass(frozen=True)
class SiteProfile:
    title_selectors: tuple[str, ...]
    content_selectors: tuple[str, ...]
    date_selectors: tuple[str, ...]
    document_id_query_keys: tuple[str, ...] = ()
    document_id_path_pattern: str | None = None
    list_ready_selector: str | None = None

# 전용 기관 프로필이 없는 사이트에 적용하는 공통 CSS 선택자
DEFAULT_PROFILE = SiteProfile(
    title_selectors=("h1", ".board-view-title", ".view-title", ".title"),
    content_selectors=("article", "main", ".board-view-content", ".view-content", ".content"),
    date_selectors=("time", "[datetime]", ".date", ".write-date", ".view-date"),
    document_id_query_keys=(
        "id",
        "seq",
        "no",
        "idx",
        "newsId",
        "articleId",
        "boardSeq",
        "boardId",
        "nttSeqNo",
        "bbsSeq",
        "bbs_seq",
    ),
    document_id_path_pattern=r"/(\d+)(?:/view)?/?$",
)

# 우선 지원하는 공공기관별 상세 페이지 수집 규칙
SITE_PROFILES = {

    # 산업통상부 보도자료
    "motir.go.kr": SiteProfile(
        title_selectors=(".detail-tit",),
        content_selectors=(".detail-cont",),
        date_selectors=(".detail-info li:has-text('등록일') span",),
        document_id_path_pattern=r"/(\d+)/view/?$",
    ),

    # 과학기술정보통신부 게시글
    "msit.go.kr": SiteProfile(
        title_selectors=(".view_head h2",),
        content_selectors=("#cont-wrap.view_cont", ".board_notcon"),
        date_selectors=(
            ".view_head .meta dl:has(dt:has-text('작성일')) dd",
            ".view_head .date",
            "time",
            "[datetime]",
        ),
        document_id_query_keys=("nttSeqNo",),
    ),

    # 기후에너지환경부 게시글
    "mcee.go.kr": SiteProfile(
        title_selectors=("#boardTableWrapper > header", ".articleSubject"),
        content_selectors=("#boardTableWrapper .view_con", ".articleDetail"),
        date_selectors=(
            "#boardViewListForRead_0 dl:has(dt:has-text('등록일자')) dd",
            ".articleInfo .item:has-text('작성일') .dd",
        ),
        document_id_query_keys=("boardId",),
    ),

    # 고용노동부 게시글
    "moel.go.kr": SiteProfile(
        title_selectors=(".b_info dl:first-child dd",),
        content_selectors=(".b_content", ".b_content.news_content"),
        date_selectors=(".b_info dl:has-text('등록일') dd",),
        document_id_query_keys=("bbs_seq", "bbsSeq"),
    ),

    # 국토교통부 게시글
    "molit.go.kr": SiteProfile(
        title_selectors=(".bd_view h4",),
        content_selectors=(".bd_view_cont", ".bd_view_ul_lst"),
        date_selectors=(".bd_view_ul_info li:has-text('등록일') span",),
        document_id_query_keys=("idx", "ID"),
    ),

    # 한국산업기술진흥원 사업공고
    "kiat.or.kr": SiteProfile(
        title_selectors=(".view_area .viewTypeA thead th",),
        content_selectors=(".view_area .viewTypeA_contents",),
        date_selectors=(
            ".view_area .viewTypeA tbody tr:has(th:has-text('공고일')) td",
        ),
        document_id_query_keys=("contents_id",),
        list_ready_selector="#contentsList a[href*='contentsView']",
    ),
}

MOLIT_TENDER_PROFILE = SiteProfile(
    title_selectors=(
        ".bd_view_tbl_info li:has(em:has-text('공사명')) span",
    ),
    content_selectors=(".bd_view",),
    date_selectors=(
        ".bd_view_tbl_info li:has(em:has-text('등록일시')) span",
    ),
    document_id_query_keys=("ID",),
)

# 산업통상부 JavaScript 링크에서 게시글 번호 추출
ARTICLE_VIEW_PATTERN = re.compile(r"article\.view\(['\"]?(\d+)['\"]?\)")

# 과기정통부 JavaScript 링크에서 게시글 번호 추출
MSIT_DETAIL_PATTERN = re.compile(r"fn_detail\(['\"]?(\d+)['\"]?\)")

# 과기정통부 fn_download(첨부그룹번호, 파일순서, 확장자)에서 다운로드 정보 추출
MSIT_DOWNLOAD_PATTERN = re.compile(
    r"fn_download\(\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?(\d+)['\"]?"
)

# 기후에너지환경부 ajaxFileDownLoad(파일 ID, 파일 순서)에서 다운로드 정보 추출
MCEE_DOWNLOAD_PATTERN = re.compile(
    r"ajaxFileDownLoad\(\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?(\d+)['\"]?"
)

# 한국산업기술진흥원 contentsView(게시글 UUID) 링크
KIAT_DETAIL_PATTERN = re.compile(r"contentsView\(['\"]([A-Za-z0-9_-]+)['\"]\)")


# URL에 맞는 기관별 프로필을 반환하고, 없으면 기본 프로필을 사용
def get_site_profile(url: str) -> SiteProfile:
    parsed = urlparse(url)
    if _hostname(url) == "molit.go.kr" and "/USR/tender/" in parsed.path:
        return MOLIT_TENDER_PROFILE
    return SITE_PROFILES.get(_hostname(url), DEFAULT_PROFILE)

# href 또는 onclick을 실제 상세 게시글 URL로 변환
def resolve_link_target(href: str | None, onclick: str | None, list_url: str) -> str | None:
    script = " ".join(value for value in (href, onclick) if value)
    hostname = _hostname(list_url)
    list_path = urlparse(list_url).path

    # 산업통상부 처리
    if hostname == "motir.go.kr":
        match = ARTICLE_VIEW_PATTERN.search(script)
        if match:
            return f"{list_url.rstrip('/')}/{match.group(1)}/view"

    # 과기정통부 처리
    if hostname == "msit.go.kr":
        match = MSIT_DETAIL_PATTERN.search(script)
        if match:
            parsed = urlparse(list_url)
            query = parse_qs(parsed.query)
            detail_query = {
                "bbsSeqNo": query.get("bbsSeqNo", ["94"])[0],
                "mId": query.get("mId", ["307"])[0],
                "mPid": query.get("mPid", ["208"])[0],
                "nttSeqNo": match.group(1),
                "sCode": query.get("sCode", ["user"])[0],
            }
            return f"{parsed.scheme}://{parsed.netloc}/bbs/view.do?{urlencode(detail_query)}"

    # 한국산업기술진흥원 처리
    if hostname == "kiat.or.kr":
        match = KIAT_DETAIL_PATTERN.search(script)
        if match:
            parsed = urlparse(list_url)
            query = parse_qs(parsed.query)
            detail_query = {
                "MenuId": query.get("MenuId", ["b159c9dac684471b87256f1e25404f5e"])[0],
                "board_id": query.get("board_id", ["90"])[0],
                "contents_id": match.group(1),
            }
            return (
                f"{parsed.scheme}://{parsed.netloc}/front/board/boardContentsView.do?"
                f"{urlencode(detail_query)}"
            )

    if hostname == "molit.go.kr" and "/USR/tender/" in list_path:
        if href and "mng.jsp" in href and "ID=" in href.upper():
            return href
        return None

    # 일반 사이트 처리
    if href and not href.lower().startswith("javascript:"):
        return href
    return None


# 일반 또는 JavaScript 첨부파일 링크를 절대 다운로드 URL로 변환
def resolve_download_url(
    href: str | None,
    page_url: str,
    onclick: str | None = None,
) -> str | None:
    script = " ".join(value for value in (href, onclick) if value)
    match = MSIT_DOWNLOAD_PATTERN.search(script)
    if match:
        download_query = urlencode(
            {
                "atchFileNo": match.group(1),
                "fileOrd": match.group(2),
                "fileBtn": "A",
            }
        )
        return urljoin(page_url, f"/ssm/file/fileDown.do?{download_query}")

    match = MCEE_DOWNLOAD_PATTERN.search(script)
    if match:
        download_query = urlencode(
            {
                "fileId": match.group(1),
                "fileSeq": match.group(2),
            }
        )
        return urljoin(page_url, f"/home/file/readDownloadFile.do?{download_query}")

    if not href:
        return None
    if not href.lower().startswith("javascript:"):
        return urljoin(page_url, href)

    match = re.search(r"location\.href\s*=\s*['\"]([^'\"]+)", href)
    if match:
        return urljoin(page_url, match.group(1))
    return None

# URL에서 www.를 제거한 소문자 도메인을 추출
def _hostname(url: str) -> str:
    return (urlparse(url).hostname or "").lower().removeprefix("www.")


def is_direct_document_url(url: str) -> bool:
    parsed = urlparse(url)
    return (
        _hostname(url) == "kiat.or.kr"
        and parsed.path.endswith("/front/board/boardContentsView.do")
        and bool(parse_qs(parsed.query).get("contents_id"))
    )
