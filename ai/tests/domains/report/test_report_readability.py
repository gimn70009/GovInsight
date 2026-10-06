from app.domains.report.presentation import submission_display_lines

APPLICANT = (
    "가. 신청자격 ㅇ 산업별 인적자원개발협의체 활동에 직접적으로 종사하고 "
    "구체적·객관적으로 공적이 입증된 자로서 정부포상업무지침에 의해 추천된 자 "
    "ㅇ 국가관, 사명감이 투철하고 공.사 생활이 타의 귀감이 되며, "
    "봉사와 선행에 앞장서는 등 능력과 품성을 두루 겸비한 자"
)


def test_source_list_becomes_two_readable_items_without_losing_conditions():
    result = submission_display_lines(APPLICANT, "제출 주체·대상")
    assert len(result) == 2
    assert result[0].startswith("산업별 인적자원개발협의체")
    assert "정부포상업무지침에 의해 추천된 자" in result[0]
    assert result[1].startswith("국가관, 사명감")
    assert "공·사 생활" in result[1]
    assert all("신청자격" not in item and "ㅇ " not in item for item in result)


def test_exceptions_dates_and_urls_are_preserved():
    value = "신청 대상: 중소기업 ㅇ 단, 참여제한 기관은 제외합니다."
    assert submission_display_lines(value, "제출 주체·대상") == [
        "중소기업", "단, 참여제한 기관은 제외합니다.",
    ]
    assert submission_display_lines("한국: https://example.org/a/b?x=1.2", "제출처·방법") == [
        "한국: https://example.org/a/b?x=1.2",
    ]
    assert submission_display_lines("표제: 2026년 10월 14일 18:00까지", "제출·의견 기한") == [
        "2026년 10월 14일 18:00까지",
    ]
    assert submission_display_lines("신청 자격이 없는 기관은 제외", "제출 주체·대상") == [
        "신청 자격이 없는 기관은 제외",
    ]
