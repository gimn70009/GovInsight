import pytest

from app.domains.report.evidence import select_report_evidence


@pytest.mark.parametrize("text", ["", "신청기간: 2026. 10. 1. ~ 10. 31.\n단, 우편은 도착분 기준."])
def test_short_source_is_preserved_exactly(text):
    excerpt = select_report_evidence(text, len(text))
    assert excerpt.text == text
    assert not excerpt.truncated
    assert excerpt.ranges == (((0, len(text)),) if text else ())


@pytest.mark.parametrize("limit", [-1, 0, 10])
def test_tiny_budget_does_not_return_a_partial_condition(limit):
    excerpt = select_report_evidence("신청기간: 2026. 10. 1. ~ 2026. 10. 31.", limit)
    assert excerpt.text == ""
    assert excerpt.ranges == ()
    assert excerpt.truncated


def test_flat_award_notice_keeps_complete_application_period_and_contact():
    period = "□ 신청기간 : 2026. 10. 7.(수) ∼ 2026. 11. 16.(월) "
    source = (
        "Ⅰ 사업개요 " + "포상 사업의 추진 배경과 관련 연혁을 설명합니다. " * 100
        + "□ 지원대상 : 기술 개발에 기여한 기업 및 개인. 단, 참여제한 대상은 제외. "
        + "Ⅱ 신청 및 접수방법 " + period
        + "□ 신청서류 ㅇ 신청서와 공적조서를 제출합니다. "
        + "□ 제출방법 : 이메일 awards@example.org로 접수. ※ 마감일 도착분만 유효. "
        + "Ⅲ 문의처 ○ 포상지원팀 02-1234-5678 awards@example.org "
        + "Ⅳ 작성요령 " + "이 부분은 서식 작성 예시와 참고 사항입니다. " * 100
    )
    excerpt = select_report_evidence(source, 1600)
    assert period in excerpt.text
    for value in ("포상지원팀", "02-1234-5678", "awards@example.org", "공적조서"):
        assert value in excerpt.text
    assert "단, 참여제한 대상은 제외" in excerpt.text
    assert "※ 마감일 도착분만 유효" in excerpt.text
    assert len(excerpt.text) <= 1600


def test_multiline_application_table_keeps_headers_values_and_adjacent_exceptions():
    table = (
        "7. 신청방법, 신청서 제출기한 및 접수처\n"
        "□ 신청방법 및 제출기한\n구분\n연구개발계획서\n신청방법\n및\n제출처\n"
        "- 계획서(필수서류) 및 첨부서류 온라인 일괄 접수 -\n"
        "* 오프라인(방문) 서류제출 불필요\n"
        "온라인 접수처\nhttps://apply.example.org/\n"
        "신청기간\n2026. 10. 1. ~ 2026. 10. 31. 18:00\n"
        "※ 시스템에서 제출 완료한 건만 인정합니다.\n"
    )
    source = "1. 사업개요\n" + "사업 추진 배경입니다.\n" * 120 + table
    source += "10. 문의처\n사업지원팀 02-1234-5678 help@example.org\n"
    source += "11. 작성요령\n" + "신청 양식의 작성 예시입니다.\n" * 100
    excerpt = select_report_evidence(source, 1800)
    assert table in excerpt.text
    assert "help@example.org" in excerpt.text
    assert len(excerpt.text) <= 1800


def test_application_fields_precede_objection_duplicate_and_post_award_procedures():
    decoys = (
        "4. 중복성 이의신청\n신청기간: 2026. 9. 1. ~ 9. 3.\n"
        "제출방법: 대표자 명의 공문을 objection@example.org로 제출합니다.\n"
        "문의처: 이의심사팀 02-1111-2222\n"
        "5. 협약 체결\n선정된 기관은 협약서류를 제출합니다.\n"
    )
    application = (
        "7. 신청방법 및 접수처\n온라인 접수: https://apply.example.org/\n"
        "신청기간: 2026. 10. 1. ~ 2026. 10. 31.\n"
        "8. 문의처\n신청지원팀 02-1234-5678 apply@example.org\n"
        "9. 신청자격\n중소기업. 단, 체납 기관 제외.\n"
    )
    source = decoys * 10 + application + "10. 참고자료\n" + "배경 설명. " * 500
    excerpt = select_report_evidence(source, 700)
    assert "https://apply.example.org/" in excerpt.text
    assert "2026. 10. 31." in excerpt.text
    assert "apply@example.org" in excerpt.text
    assert "단, 체납 기관 제외" in excerpt.text


def test_oversized_earlier_candidate_does_not_hide_a_later_complete_period():
    source = (
        "□ 신청기간: " + "여러 신청자격 및 접수 방법과 문의 내용을 설명하며 " * 80
        + "□ 접수기간: 2026. 10. 1. ~ 2026. 10. 31. "
        + "□ 문의처: 접수지원팀 02-1234-5678 "
    )
    excerpt = select_report_evidence(source, 300)
    assert "접수기간: 2026. 10. 1. ~ 2026. 10. 31." in excerpt.text
    assert "여러 신청자격" not in excerpt.text


def test_metadata_points_to_exact_original_slices_and_discloses_gaps():
    source = (
        "□ 사업개요 " + "배경 및 정책 설명입니다. " * 200
        + "□ 신청기간: 2026. 10. 1. ~ 2026. 10. 31. "
        + "□ 기타사항 " + "추가 배경입니다. " * 200
        + "□ 문의처: 지원팀 02-1234-5678 help@example.org"
    )
    excerpt = select_report_evidence(source, 500)
    assert excerpt.truncated
    assert excerpt.original_chars == len(source)
    assert 0 < len(excerpt.text) <= 500
    assert "[일부 원문 생략: 누락 부분은 확인되지 않음]" in excerpt.text
    assert all(source[start:end] in excerpt.text for start, end in excerpt.ranges)
    assert all(left[1] < right[0] for left, right in zip(excerpt.ranges, excerpt.ranges[1:]))
    assert excerpt.metadata()["selectedChars"] == sum(end - start for start, end in excerpt.ranges)


def test_repeated_complete_guidance_does_not_consume_the_whole_budget():
    repeated = "□ 신청기간: 2026. 10. 1. ~ 2026. 10. 31.\n"
    source = repeated * 30 + "□ 문의처: 지원팀 02-1234-5678 help@example.org\n"
    excerpt = select_report_evidence(source, 300)
    assert excerpt.text.count("신청기간:") == 1
    assert "help@example.org" in excerpt.text

def test_secondary_procedure_keeps_its_scope_heading_when_selected():
    source = (
        "1. 사업개요\n" + "배경 설명입니다. " * 250
        + "4. 중복성 이의신청\n"
        + "신청기간: 2026. 9. 1. ~ 2026. 9. 3.\n"
        + "제출방법: 대표자 명의 공문으로 제출합니다.\n"
        + "5. 참고자료\n" + "추가 자료입니다. " * 250
    )
    excerpt = select_report_evidence(source, 400)
    assert "신청기간: 2026. 9. 1. ~ 2026. 9. 3." in excerpt.text
    assert "4. 중복성 이의신청" in excerpt.text
