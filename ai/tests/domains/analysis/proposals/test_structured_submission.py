import pytest

from app.domains.analysis.proposals.submission_requirements import collect_submission_requirements
from app.domains.monitoring.parsers.docx_parser import DocxParser
from tests.domains.analysis.proposals.test_submission_requirements import request
from tests.domains.monitoring.test_office_parsers import docx_bytes

TEXT = """신청서류
[표]
서류명\t주요내용\t구분\t제출조건
사업계획서\t운영·지원 사업계획서\t필수\t
증빙자료\t사업계획 내 기재사항 증빙 등\t해당 시\t증빙할 내용이 있는 기관
발표자료\t사업계획서 기반 발표자료\t선택\t
기업신용평가등급 확인서\t신청·접수 기간 내 평가된 확인서\t해당 시\t민간기관
추천서\t\t\t
[/표]
"""


def test_cell_boundaries_do_not_turn_explanations_into_documents_or_leak_conditions():
    rows = collect_submission_requirements(request(TEXT))
    by_name = {row.title: row for row in rows}
    assert set(by_name) == {"사업계획서", "증빙자료", "발표자료", "기업신용평가등급 확인서"}
    assert "운영·지원 사업계획서" not in by_name
    assert "신청·접수 기간 내 평가된 확인서" not in by_name
    assert by_name["사업계획서"].level == "MANDATORY"
    assert by_name["사업계획서"].condition == ""
    assert by_name["기업신용평가등급 확인서"].condition == "민간기관"
    assert "추천서" not in by_name
    assert all(row.source.excerpt in TEXT for row in rows)


def test_actual_docx_parse_reaches_reconciliation_with_only_declared_merges(tmp_path):
    path = tmp_path / "공고.docx"
    path.write_bytes(docx_bytes())
    text = DocxParser().parse(path).text
    rows = collect_submission_requirements(request(text, name=path.name))
    assert [row.title for row in rows] == ["신청서", "사업계획서"]
    assert all(row.level == "MANDATORY" for row in rows)


def test_partial_table_and_reference_sections_do_not_create_requirements():
    assert not collect_submission_requirements(request(TEXT.replace("[/표]", "")))
    assert not collect_submission_requirements(request("작성 예시\n" + TEXT))
    assert not collect_submission_requirements(request(TEXT.replace("신청서류", "제출서류")))


def test_shared_exemptions_remain_for_complete_source_analysis():
    assert not collect_submission_requirements(request(TEXT + "* 개인기업은 확인서 제출 면제\n"))


def test_merged_header_with_conditional_marker_and_parenthesized_title():
    text = ("신청서류\n[표]\n제출 서류\t제출 서류\t비고\n"
            "해당 시\t확인서(민간기관)\t민간기관\n필수\t사업계획서\tHWP\n[/표]\n")
    rows = collect_submission_requirements(request(text))
    assert [row.title for row in rows] == ["확인서(민간기관)", "사업계획서"]
    assert rows[0].level == "CONDITIONAL"
    assert rows[1].level == "MANDATORY"


def test_ambiguous_merged_header_does_not_choose_a_document_column():
    text = ("신청서류\n[표]\n제출 서류\t제출 서류\t구분\n"
            "신청서\t계획서\t필수\n확인서\t동의서\t필수\n[/표]")
    assert not collect_submission_requirements(request(text))


@pytest.mark.parametrize("header", ["제출서류 목록", "신청서류 목록", "구비서류 목록", "서류명"])
def test_document_list_header_aliases_preserve_explicit_columns(header):
    text = f"신청서류\n[표]\n{header}\t구분\n사업계획서\t필수\n[/표]"
    rows = collect_submission_requirements(request(text))
    assert [row.title for row in rows] == ["사업계획서"]
    assert rows[0].level == "MANDATORY"
