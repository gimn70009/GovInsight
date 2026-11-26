import json
from pathlib import Path

from app.domains.analysis.proposals.submission_requirements import collect_submission_requirements
from tests.domains.analysis.proposals.test_submission_requirements import request

CASE = json.loads(
    (
        Path(__file__).resolve().parents[5]
        / "test-fixtures/document-tables/technology-sharing.json"
    ).read_text("utf-8")
)


def test_real_three_rows_keep_notes_footnote_and_required_marker():
    text = CASE["submission"]
    items = collect_submission_requirements(request(text))
    assert [item.title for item in items] == CASE["expected_titles"]
    assert all(item.level == "MANDATORY" and item.stage == "APPLICATION" for item in items)
    assert "ZIP" in items[0].source.excerpt
    assert "PDF" in items[1].source.excerpt
    assert all("접수 마감일 기준" in item.source.excerpt for item in items)
    assert all(item.source.excerpt in text for item in items)


def test_second_document_bullet_never_becomes_a_note_of_the_first():
    text = CASE["submission"].replace(
        "* 신청 특허별로 작성하되 전체 ZIP 형태로 업로드", "• 별도 협약서"
    )
    items = collect_submission_requirements(request(text))
    assert CASE["expected_titles"][0] not in [item.title for item in items]
