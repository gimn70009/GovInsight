import json
from pathlib import Path

import pytest

from app.domains.submission.quantities import comparison_quote
from app.domains.submission.schemas import Document, Note, NotesRequest, Source
from app.domains.submission.service import valid_note

CASES = json.loads(
    (
        Path(__file__).resolve().parents[4] / "test-fixtures/submission-quantity-cases.json"
    ).read_text(encoding="utf-8")
)


@pytest.mark.parametrize("case", CASES)
def test_only_proven_structural_numbers_are_excluded(case):
    assert comparison_quote(case["row"], case["title"], case["row"]) == case["expected"]
    assert comparison_quote("다른 인용 5", case["title"], case["row"]) == "다른 인용 5"


def test_realistic_table_response_keeps_quantities_without_repeating_row_number():
    title = "확인서(개인해당시)"
    row = "5\t" + title + "\t각 1부\t기관별 1부"
    request = NotesRequest(
        sources=[Source(id="s0", text=row)],
        incomplete=False,
        documents=[
            Document(
                id="d0",
                title=title,
                sourceId="s0",
                excerpt=row,
                stage="APPLICATION",
                appliesTo=None,
            )
        ],
    )
    note = Note(
        sourceId="s0",
        quote=row,
        scopeQuote=row,
        scope="DOCUMENT",
        documentId="d0",
        appliesTo=None,
        text="확인서는 개인에 해당하는 경우 기관별로 각 1부 제출해야 합니다.",
    )
    assert valid_note(note, request)
    for wrong in [
        note.text.replace("1부", "2부"),
        note.text.replace("1부", "1매"),
        note.text.replace("각 1부 ", ""),
        note.text + " 5부를 더 제출합니다.",
    ]:
        assert not valid_note(note.model_copy(update={"text": wrong}), request)
    assert note.quote == row
