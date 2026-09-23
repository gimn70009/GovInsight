import pytest

from app.domains.analysis.legal.style import has_formal_style, normalize_legal_narrative


@pytest.mark.parametrize(("original", "expected"), [
    ("성과는 기관 소유이다. 다만 합의로 달리 정할 수 있다. 사전 확인이 필요하다. 협약 존재 여부 확인",
     "성과는 기관 소유입니다. 다만 합의로 달리 정할 수 있습니다. 사전 확인이 필요합니다. 협약 존재 여부를 확인할 필요가 있습니다."),
    ("지원 비율은 75.5%이다. 의무는 아니다.", "지원 비율은 75.5%입니다. 의무는 아닙니다."),
    ("추가 확인이 필요합니다", "추가 확인이 필요합니다."),
])
def test_repairs_only_known_endings_without_changing_facts(original, expected):
    assert normalize_legal_narrative(original) == expected
    assert normalize_legal_narrative(expected) == expected
    assert has_formal_style(expected)


def test_unknown_fragments_require_repair_instead_of_inventing_a_sentence():
    text = "실시권 범위 및 계약 조건"
    assert normalize_legal_narrative(text) == text
    assert not has_formal_style(text)
