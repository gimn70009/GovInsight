import pytest

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


@pytest.mark.parametrize("escaped_dots", [False, True], ids=["literal", "legacy-escaped"])
def test_six_supported_boards_select_all_thirteen_requested_posts(escaped_dots: bool) -> None:
    sources = [
        (
            "https://www.motir.go.kr",
            "/kor/article/ATCL2826a2625",
            "/kor/article/ATCL2826a2625/",
            "/kor/article/ATCL2826a2625/{}/view",
            2,
        ),
        (
            "https://www.msit.go.kr",
            "/bbs/list.do?bbsSeqNo=100",
            "/bbs/view.do",
            "/bbs/view.do?bbsSeqNo=100&nttSeqNo={}",
            2,
        ),
        (
            "https://mcee.go.kr",
            "/home/web/board/list.do?boardMasterId=39",
            "/home/web/board/read.do",
            "/home/web/board/read.do?boardId={}",
            2,
        ),
        (
            "https://www.moel.go.kr",
            "/info/govsupport/govsupportcon/govSupportSubList.do",
            "govSupportSubView.do",
            "/info/govsupport/govsupportcon/govSupportSubView.do?bbs_seq={}",
            2,
        ),
        (
            "https://www.molit.go.kr",
            "/USR/BORD0201/m_69/LST.jsp?id=N01_B",
            "/USR/BORD0201/m_69/DTL.jsp",
            "/USR/BORD0201/m_69/DTL.jsp?id=N01_B&idx={}",
            2,
        ),
        (
            "https://www.kiat.or.kr",
            "/front/board/boardContentsListPage.do?board_id=90",
            "/front/board/boardContentsView.do",
            "/front/board/boardContentsView.do?board_id=90&contents_id={}",
            3,
        ),
    ]
    selected_by_source = []
    expected_by_source = []
    for origin, list_path, pattern, detail_template, count in sources:
        # Only these three presets were previously persisted with regex-style dots.
        if escaped_dots and origin in {
            "https://www.msit.go.kr",
            "https://www.moel.go.kr",
            "https://www.kiat.or.kr",
        }:
            pattern = pattern.replace(".", r"\.")
        details = [detail_template.format(index) for index in range(5, 0, -1)]
        selected_by_source.append(
            select_document_urls(
                [
                    list_path,
                    "/about",
                    "https://other.go.kr" + details[0],
                    details[0] + "#content",
                    *details,
                ],
                origin + list_path,
                pattern,
                count,
            )
        )
        expected_by_source.append([origin + detail for detail in details[:count]])

    assert sum(map(len, selected_by_source)) == 13
    assert selected_by_source == expected_by_source


def test_escaped_dot_compatibility_keeps_other_pattern_characters_literal() -> None:
    assert select_document_urls(
        [
            "/viewXdo?category=AAAB1",
            "/view.do?category=A+B[1]&id=3",
            "/viewXdo?category=A+B[1]&id=2",
            "/view.do?category=AB1&id=1",
        ],
        "https://example.go.kr/list.do",
        r"/view\.do?category=A+B[1]",
        4,
    ) == ["https://example.go.kr/view.do?category=A+B[1]&id=3"]


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
