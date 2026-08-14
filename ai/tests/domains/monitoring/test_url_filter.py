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
