import json
from pathlib import Path

import pytest

from app.domains.analysis.proposals.submission_requirements import collect_submission_requirements
from app.domains.analysis.proposals.submission_validation import validate_submission_documents
from tests.domains.analysis.proposals.test_submission_requirements import request

CASE = json.loads(
    (
        Path(__file__).resolve().parents[5] / "test-fixtures/document-tables/disaster-notice.json"
    ).read_text("utf-8")
)


@pytest.mark.parametrize("header", ["목록", "내역", "자료 이름"])
def test_real_multirow_matrix_survives_validation_without_model(header):
    text = CASE["submission"].replace("목록", header)
    rows = collect_submission_requirements(request(text))
    assert [r.title for r in rows] == CASE["expected_titles"]
    assert [r.level for r in rows] == CASE["expected_levels"]
    for row, roles in zip(rows, CASE["expected_roles"], strict=True):
        assert all(role in row.condition.split(" / ")[0] for role in roles)
        assert row.source.excerpt in text
    items, notes = validate_submission_documents([], request(text))
    assert [i.title for i in items] == CASE["expected_titles"]
    assert not notes


def test_matrix_never_infers_a_blank_role_or_discards_an_exception():
    text = CASE["submission"]
    damaged = text.replace("○\tX\tX", "○\t\tX", 1)
    rows = collect_submission_requirements(request(damaged))
    assert CASE["expected_titles"][4] not in [r.title for r in rows]
    assert not collect_submission_requirements(request(text + "\n※ 일부 기관은 제출 면제"))
    assert not collect_submission_requirements(request(text.replace("[/표]", "")))
    _, notes = validate_submission_documents([], request(damaged))
    assert any("1개 행" in note and "자동 검증하지 못했습니다" in note for note in notes)


def test_same_shape_outside_submission_context_is_not_an_obligation():
    text = CASE["submission"].split("[표]", 1)[1]
    text = "[표]" + text.replace("별첨 서류 목록", "평가항목")
    assert not collect_submission_requirements(request(text))


@pytest.mark.parametrize("position", [0, 2, 4])
def test_unknown_first_row_mark_cannot_remove_a_role_column(position):
    rows = CASE["submission"].splitlines()
    data_rows = [i for i, line in enumerate(rows) if line.startswith("별첨")]
    column = 2 + position // 2
    cells = rows[data_rows[0]].split("\t")
    cells[column] = ""
    rows[data_rows[0]] = "\t".join(cells)
    items, notes = validate_submission_documents([], request("\n".join(rows)))
    assert len(items) == 12
    assert CASE["expected_titles"][0] not in [item.title for item in items]
    assert any("1개 행" in note for note in notes)


def test_whole_empty_role_column_and_leading_exemption_remain_unresolved():
    lines = CASE["submission"].splitlines()
    for i, line in enumerate(lines):
        if line.startswith("별첨"):
            cells = line.split("\t")
            cells[-1] = ""
            lines[i] = "\t".join(cells)
    items, notes = validate_submission_documents([], request("\n".join(lines)))
    assert not items and any("13개 행" in note for note in notes)
    assert not collect_submission_requirements(
        request(CASE["submission"].replace("[표]", "※ 대학은 제출 면제\n[표]"))
    )
