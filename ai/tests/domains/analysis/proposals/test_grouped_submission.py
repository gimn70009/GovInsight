import json
from pathlib import Path

import pytest

from app.domains.analysis.proposals.submission_requirements import collect_submission_requirements
from app.domains.analysis.proposals.submission_validation import validate_submission_documents
from tests.domains.analysis.proposals.test_submission_requirements import request

CASES = json.loads((Path(__file__).resolve().parents[5] /
                   "test-fixtures/document-tables/grouped-submission-cases.json").read_text("utf-8"))


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_grouped_cells_reach_validated_checklist(case):
    document = request(case["text"])
    rows = collect_submission_requirements(document)
    assert [r.title for r in rows] == case["titles"]
    assert [r.level for r in rows] == case["levels"]
    assert [r.stage for r in rows] == case["stages"]
    for row, condition in zip(rows, case.get("conditions", [])):
        assert condition in row.condition
    assert all(r.source.excerpt in case["text"] for r in rows)
    verified, notes = validate_submission_documents([], document)
    assert [i.title for i in verified] == case["titles"]
    assert not notes


def test_missing_table_end_cannot_promote_partial_list():
    case = CASES[0]
    assert not collect_submission_requirements(request(case["text"].replace("[/표]", "")))
