import pytest

from app.domains.monitoring.parsers.ocr_selection import select_ocr_pages


def test_submission_and_eligibility_take_priority_over_cover_and_unrelated_tables():
    texts = ["표지", "일반 표 A\tB\nC\tD", "3. 신청 자격\n중소기업만 신청 가능",
             "4. 제출서류\n신청서 및 확인서", "세부 안내"]
    assert select_ocr_pages(texts, [1, 2, 3], 2) == [3, 2]


def test_table_of_contents_does_not_consume_ocr_budget():
    texts = ["목차\n신청자격 .... 3\n제출서류 .... 5", "신청 자격\n지원 대상",
             "제출 서류\n구비 서류"]
    assert select_ocr_pages(texts, [0, 1, 2], 2) == [2, 1]


@pytest.mark.parametrize("heading", [
    "신청자격", "지원 대상", "참여 요건", "지원 제외", "신청 제한", "신청 불가", "자격 요건",
])
def test_eligibility_and_exclusion_variants_are_selected(heading):
    assert select_ocr_pages(["제출서류\n신청서", heading + "\n기업 요건"], [], 2) == [0, 1]


def test_both_topics_on_one_page_are_not_requested_twice():
    assert select_ocr_pages(
        ["제출서류\n신청서\n신청자격\n중소기업", "일반 표"], [0, 1], 2,
    ) == [0]


def test_specific_heading_wins_over_short_incidental_reference():
    texts = ["자세한 제출서류는 다음 절을 참고하십시오. " * 8,
             "4. 제출 서류\n사업계획서", "2. 지원대상\n기업"]
    assert select_ocr_pages(texts, [0, 1, 2], 2) == [1, 2]


def test_scans_without_text_use_bounded_page_order():
    assert select_ocr_pages(["", "", ""], [0, 1, 2], 2) == [0, 1]
    assert select_ocr_pages(["", "", ""], [0, 1, 2], 1) == [0]
