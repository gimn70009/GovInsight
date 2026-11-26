"""Display is model-owned; Python checks existence without rewriting or word filters."""

import json
from pathlib import Path

import pytest

from app.domains.participation import focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source

CASES = json.loads(
    (
        Path(__file__).resolve().parents[4]
        / "backend/src/test/resources/participation-conditions/display-quality.json"
    ).read_text(encoding="utf-8")
)


@pytest.mark.parametrize("case", CASES, ids=[case["name"] for case in CASES])
def test_model_owned_display_is_not_changed_by_legacy_readability_rules(case):
    source = Source(id="one", excerpt="원문에 명시된 신청 요건", group_label="신청자격")
    request = RewriteRequest(
        criteria=[Criterion(code=code, label=code, category="신청 자격") for code in focused.CODES],
        sources=[source],
    )
    text = case["text"]
    candidate = focused.ExtractedCondition(
        source_id=source.id, criterion="BUSINESS_FORM", kind="ELIGIBILITY", text=text, quote="L1",
    )
    result = focused.project(
        focused.Extraction(items=[candidate], unresolved_source_ids=[]), [source], request,
    )
    assert [statement.text for statement in result.statements] == [text.strip()]
    assert result.statements[0].supporting_quotes == [source.excerpt]
