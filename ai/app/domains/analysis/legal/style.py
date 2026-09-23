"""Normalize known prose endings only; source quotations never pass through this function."""

import re

from app.domains.analysis.proposals.korean import verify_korean_body


def normalize_legal_narrative(text: str) -> str:
    value = text.strip()
    for plain, polite in (
        ("아니다", "아닙니다"), ("있다", "있습니다"), ("없다", "없습니다"),
        ("한다", "합니다"), ("된다", "됩니다"), ("하다", "합니다"), ("이다", "입니다"),
        ("다르다", "다릅니다"), ("따른다", "따릅니다"), ("했다", "했습니다"),
    ):
        value = re.sub(plain + r"(?=[.!?](?:\s|$)|$)", polite, value)
    value = re.sub(r"여부 확인(?=\.?$)", "여부를 확인할 필요가 있습니다", value)
    value = re.sub(r"확인 필요(?=\.?$)", "확인이 필요합니다", value)
    value = re.sub(r"확인(?=\.?$)", "확인이 필요합니다", value)
    if re.search(r"[가-힣]니다$", value):
        value += "."
    return value


def has_formal_style(text: str) -> bool:
    try:
        verify_korean_body(text)
    except ValueError:
        return False
    return True
