from app.domains.monitoring.collectors.site_profiles import (
    get_site_profile,
    resolve_download_url,
    resolve_link_target,
)


def test_resolve_motir_javascript_article_link() -> None:
    result = resolve_link_target(
        "javascript:article.view('172106');",
        None,
        "https://www.motir.go.kr/kor/article/ATCL3f49a5a8c",
    )

    assert result == "https://www.motir.go.kr/kor/article/ATCL3f49a5a8c/172106/view"


def test_resolve_msit_onclick_detail_link() -> None:
    result = resolve_link_target(
        "javascript:;",
        "fn_detail(3187665);",
        (
            "https://www.msit.go.kr/bbs/list.do?sCode=user&mId=307"
            "&mPid=208&bbsSeqNo=94"
        ),
    )

    assert result == (
        "https://www.msit.go.kr/bbs/view.do?bbsSeqNo=94&mId=307"
        "&mPid=208&nttSeqNo=3187665&sCode=user"
    )


def test_resolve_javascript_download_link() -> None:
    result = resolve_download_url(
        "javascript:location.href='/attach/down/file-id'",
        "https://www.motir.go.kr/kor/article/ATCL3f49a5a8c/1/view",
    )

    assert result == "https://www.motir.go.kr/attach/down/file-id"


def test_get_profile_ignores_www_subdomain() -> None:
    profile = get_site_profile("https://www.moel.go.kr/news/enews/report/enewsView.do")

    assert profile.title_selectors == (".b_info dl:first-child dd",)
