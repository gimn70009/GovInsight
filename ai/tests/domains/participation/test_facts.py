import json
from pathlib import Path

import pytest

from app.domains.participation import facts

CASES = json.loads(
    (
        Path(__file__).resolve().parents[4]
        / "backend/src/test/resources/participation-conditions/semantic-facts.json"
    ).read_text(encoding="utf-8")
)


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_facts_preserve_meaning_and_allow_readable_paraphrases(case):
    assert facts.equivalent(case["text"], case["quote"]) is case["equivalent"]
