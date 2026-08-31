from app.domains.monitoring.collectors.site_profiles import (
    get_site_profile,
    is_direct_document_url,
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


def test_resolve_msit_javascript_download_link() -> None:
    result = resolve_download_url(
        "javascript:void(0);",
        "https://www.msit.go.kr/bbs/view.do?bbsSeqNo=100",
        "fn_download('54751', '3', 'hwpx');",
    )

    assert result == (
        "https://www.msit.go.kr/ssm/file/fileDown.do?"
        "atchFileNo=54751&fileOrd=3&fileBtn=A"
    )


def test_resolve_mcee_javascript_download_link() -> None:
    result = resolve_download_url(
        "javascript:ajaxFileDownLoad('329108','1');",
        "https://mcee.go.kr/home/web/board/read.do?boardId=1880370",
    )

    assert result == (
        "https://mcee.go.kr/home/file/readDownloadFile.do?"
        "fileId=329108&fileSeq=1"
    )


def test_get_profile_ignores_www_subdomain() -> None:
    profile = get_site_profile("https://www.moel.go.kr/news/enews/report/enewsView.do")

    assert profile.title_selectors == (".b_info dl:first-child dd",)
    assert profile.document_id_query_keys == ("bbs_seq", "bbsSeq")


def test_resolve_molit_tender_detail_link_only() -> None:
    list_url = "https://www.molit.go.kr/USR/tender/m_83/lst.jsp"

    detail = resolve_link_target(
        "./mng.jsp?SECTION=선택없음&ID=32699&TYPE=VIEW",
        None,
        list_url,
    )
    category = resolve_link_target("./lst.jsp?SECTION=공사", None, list_url)

    assert detail == "./mng.jsp?SECTION=선택없음&ID=32699&TYPE=VIEW"
    assert category is None


def test_get_molit_tender_profile() -> None:
    profile = get_site_profile(
        "https://www.molit.go.kr/USR/tender/m_83/mng.jsp?ID=32699"
    )

    assert profile.content_selectors == (".bd_view",)


def test_profiles_keep_document_id_rules_with_each_institution() -> None:
    assert get_site_profile("https://www.msit.go.kr/bbs/view.do").document_id_query_keys == (
        "nttSeqNo",
    )
    assert get_site_profile("https://mcee.go.kr/home/web/board/read.do").document_id_query_keys == (
        "boardId",
    )
    assert get_site_profile("https://www.motir.go.kr/article/1/view").document_id_path_pattern == (
        r"/(\d+)/view/?$"
    )


def test_resolve_kiat_contents_view_link() -> None:
    result = resolve_link_target(
        "javascript:contentsView('6cbbabc261de4a2dabe7fc1fdb226da2')",
        None,
        (
            "https://www.kiat.or.kr/front/board/boardContentsListPage.do?"
            "MenuId=b159c9dac684471b87256f1e25404f5e&board_id=90"
        ),
    )

    assert result == (
        "https://www.kiat.or.kr/front/board/boardContentsView.do?"
        "MenuId=b159c9dac684471b87256f1e25404f5e&board_id=90&"
        "contents_id=6cbbabc261de4a2dabe7fc1fdb226da2"
    )


def test_get_kiat_profile_and_direct_document_url() -> None:
    url = (
        "https://www.kiat.or.kr/front/board/boardContentsView.do?"
        "board_id=90&contents_id=6cbbabc261de4a2dabe7fc1fdb226da2"
    )

    profile = get_site_profile(url)

    assert profile.title_selectors == (".view_area .viewTypeA thead th",)
    assert profile.document_id_query_keys == ("contents_id",)
    assert profile.list_ready_selector == "#contentsList a[href*='contentsView']"
    assert is_direct_document_url(url) is True
