from app.domains.monitoring.collectors.url_filter import (
    extract_external_document_id,
    select_document_urls,
)


def test_select_document_urls_normalizes_filters_and_deduplicates() -> None:
    urls = select_document_urls(
        [
            "/notice/view?id=3#content",
            "https://example.go.kr/notice/view?id=3",
            "/notice/view?id=2",
            "https://other.go.kr/notice/view?id=1",
            "/about",
        ],
        "https://example.go.kr/notices",
        "/notice/view",
        2,
    )

    assert urls == [
        "https://example.go.kr/notice/view?id=3",
        "https://example.go.kr/notice/view?id=2",
    ]


def test_select_document_urls_without_pattern_keeps_same_origin_links() -> None:
    urls = select_document_urls(
        ["/notices", "/notice/3", "mailto:admin@example.go.kr", "https://other.go.kr/1"],
        "https://example.go.kr/notices",
        None,
        3,
    )

    assert urls == ["https://example.go.kr/notice/3"]


def test_select_document_urls_removes_session_id_from_path() -> None:
    urls = select_document_urls(
        ["/m/mob/board/read.do;jsessionid=ABC123?boardId=10"],
        "https://mcee.go.kr/m/mob/board/list.do",
        "/board/read.do",
        1,
    )

    assert urls == ["https://mcee.go.kr/m/mob/board/read.do?boardId=10"]


def test_extract_external_document_id_prefers_known_query_parameter() -> None:
    assert extract_external_document_id(
        "https://www.korea.kr/briefing/pressReleaseView.do?newsId=156742404"
    ) == "156742404"
    assert extract_external_document_id("https://example.go.kr/view?boardSeq=123") == "123"
    assert extract_external_document_id("https://example.go.kr/notices/456") == "456"
    assert extract_external_document_id(
        "https://www.motir.go.kr/kor/article/ATCL3f49a5a8c/172106/view"
    ) == "172106"


def test_extract_external_document_id_supports_institution_query_parameters() -> None:
    assert extract_external_document_id(
        "https://www.msit.go.kr/bbs/view.do?nttSeqNo=3186858"
    ) == "3186858"
    assert extract_external_document_id(
        "https://mcee.go.kr/home/web/board/read.do?boardId=1880370"
    ) == "1880370"
    assert extract_external_document_id(
        "https://www.moel.go.kr/info/view.do?bbs_seq=12345"
    ) == "12345"
    assert extract_external_document_id(
        "https://www.molit.go.kr/USR/BORD0201/DTL.jsp?idx=269284"
    ) == "269284"
    assert extract_external_document_id(
        "https://www.kiat.or.kr/front/board/boardContentsView.do?"
        "contents_id=6cbbabc261de4a2dabe7fc1fdb226da2"
    ) == "6cbbabc261de4a2dabe7fc1fdb226da2"


def test_extract_external_document_id_never_returns_page_file_name() -> None:
    assert extract_external_document_id("https://example.go.kr/board/read.do") is None
    assert extract_external_document_id("https://example.go.kr/board/view.do") is None
    assert extract_external_document_id("https://example.go.kr/board/DTL.jsp") is None
    assert extract_external_document_id("https://example.go.kr/board/list.html") is None


def test_extract_external_document_id_handles_case_and_encoded_value() -> None:
    assert extract_external_document_id(
        "https://example.go.kr/view.do?NTTSEQNO=ABC-123"
    ) == "ABC-123"
    assert extract_external_document_id(
        "https://example.go.kr/view.do?articleId=ABC%5F123"
    ) == "ABC_123"
