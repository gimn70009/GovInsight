"""The model and local completion must satisfy the same display contract."""

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
def test_shared_display_quality(case):
    assert focused.display_quality(case["text"]) is case["readable"]


def project(quote, text=None, code="BUSINESS_FORM"):
    source = Source(id="one", excerpt=quote, group_label="신청자격")
    request = RewriteRequest(
        criteria=[Criterion(code=c, label=c, category="참여기업 조건") for c in focused.CODES],
        sources=[source],
    )
    candidate = focused.ExtractedCondition(
        source_id="one", criterion=code, kind="ELIGIBILITY", text=text,
        quote="L1", basis="H0",
    )
    return focused.project(
        focused.Extraction(items=[candidate], unresolved_source_ids=[]), [source], request
    )


@pytest.mark.parametrize(
    "quote",
    [
        "중소기업기본법 제2조에 따라 공고일 기준 업 력 7년 년 이상 이 상의 의중중소 소기기업",
        "및 방법 신청자격 :판로지원법 제2조에 따른 공공기관",
        "중소기업의 특허를 보유한 의",
        "중소기업 지원 대상 중 다음에 해당하는",
        "중소기업의 매출액 100억원 이상. 단,",
        "중소기업의 매출액 100억원 이\ufffd상",
    ],
)
@pytest.mark.parametrize("with_bad_text", [False, True])
def test_missing_or_bad_model_sentence_never_launders_damaged_source(quote, with_bad_text):
    result = project(quote, quote + "이어야 합니다." if with_bad_text else None)
    assert not result.statements
    assert result.evidence_only_ids == ["one"]


def test_null_text_can_complete_a_known_clause_and_preserve_exact_evidence():
    quote = "중소기업기본법 제2조에 따른 중소기업"
    result = project(quote)
    assert result.statements[0].text == quote + "이어야 합니다."
    assert result.statements[0].supporting_quotes == [quote]


def test_null_text_can_keep_an_already_complete_source_sentence():
    quote = "공고일 기준 업력 7년 이상인 중소기업이 지원 대상입니다."
    result = project(quote)
    assert result.statements[0].text == quote
    assert result.statements[0].supporting_quotes == [quote]


def test_clean_model_sentence_can_remove_decoration_without_changing_quote():
    quote = "□ 지원대상: 중소기업기본법 제2조에 따른 중소기업"
    text = "중소기업기본법 제2조에 따른 중소기업이 지원 대상입니다."
    result = project(quote, text)
    assert result.statements[0].text == text
    assert result.statements[0].supporting_quotes == [quote]


def test_invalid_model_decoration_can_recover_a_clean_grounded_clause():
    quote = "중소기업기본법 제2조에 따른 중소기업"
    result = project(quote, "\ue000" + quote + "이 지원 대상입니다.")
    assert result.statements[0].text == quote + "이어야 합니다."
    assert result.statements[0].supporting_quotes == [quote]


def test_an_overlong_source_is_never_sliced_into_a_display_sentence():
    quote = "중소기업 중 " + "제조데이터를 수집하고 " * 22 + "특허를 보유한 기업"
    assert 240 < len(quote) < 600
    result = project(quote)
    assert not result.statements
    assert result.evidence_only_ids == ["one"]
