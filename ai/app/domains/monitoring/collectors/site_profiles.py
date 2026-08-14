import re
from dataclasses import dataclass
from urllib.parse import parse_qs, urlencode, urljoin, urlparse


@dataclass(frozen=True)
class SiteProfile:
    title_selectors: tuple[str, ...]
    content_selectors: tuple[str, ...]
    date_selectors: tuple[str, ...]


DEFAULT_PROFILE = SiteProfile(
    title_selectors=("h1", ".board-view-title", ".view-title", ".title"),
    content_selectors=("article", "main", ".board-view-content", ".view-content", ".content"),
    date_selectors=("time", "[datetime]", ".date", ".write-date", ".view-date"),
)

SITE_PROFILES = {
    "motir.go.kr": SiteProfile(
        title_selectors=(".detail-tit",),
        content_selectors=(".detail-cont",),
        date_selectors=(".detail-info li:has-text('등록일') span",),
    ),
    "msit.go.kr": SiteProfile(
        title_selectors=(".view_head h2",),
        content_selectors=(".board_notcon",),
        date_selectors=(".view_head .date", "time", "[datetime]"),
    ),
    "mcee.go.kr": SiteProfile(
        title_selectors=(".articleSubject",),
        content_selectors=(".articleDetail",),
        date_selectors=(".articleInfo .item:has-text('작성일') .dd",),
    ),
    "moel.go.kr": SiteProfile(
        title_selectors=(".b_info dl:first-child dd",),
        content_selectors=(".b_content.news_content",),
        date_selectors=(".b_info dl:has-text('등록일') dd",),
    ),
    "molit.go.kr": SiteProfile(
        title_selectors=(".bd_view h4",),
        content_selectors=(".bd_view_ul_lst",),
        date_selectors=(".bd_view_ul_info li:has-text('등록일') span",),
    ),
}

ARTICLE_VIEW_PATTERN = re.compile(r"article\.view\(['\"]?(\d+)['\"]?\)")
MSIT_DETAIL_PATTERN = re.compile(r"fn_detail\(['\"]?(\d+)['\"]?\)")


def get_site_profile(url: str) -> SiteProfile:
    return SITE_PROFILES.get(_hostname(url), DEFAULT_PROFILE)


def resolve_link_target(href: str | None, onclick: str | None, list_url: str) -> str | None:
    script = " ".join(value for value in (href, onclick) if value)
    hostname = _hostname(list_url)

    if hostname == "motir.go.kr":
        match = ARTICLE_VIEW_PATTERN.search(script)
        if match:
            return f"{list_url.rstrip('/')}/{match.group(1)}/view"

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

    if href and not href.lower().startswith("javascript:"):
        return href
    return None


def resolve_download_url(href: str | None, page_url: str) -> str | None:
    if not href:
        return None
    if not href.lower().startswith("javascript:"):
        return urljoin(page_url, href)

    match = re.search(r"location\.href\s*=\s*['\"]([^'\"]+)", href)
    if match:
        return urljoin(page_url, match.group(1))
    return None


def _hostname(url: str) -> str:
    return (urlparse(url).hostname or "").lower().removeprefix("www.")
