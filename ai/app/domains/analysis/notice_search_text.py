"""Deterministic, bounded inputs for notice embeddings; no model calls here."""

import re
import unicodedata

_UNKNOWN = re.compile(
    r"(?:(?:원문|본문|자료|첨부파일)(?:에서|에)?\s*)?"
    r"(?:(?:사업\s*목적|지원\s*대상|신청\s*자격|협력\s*구조|필수\s*파트너)(?:을|를|은|는|이|가)?\s*)?"
    r"(?:확인하지\s*못했습니다|확인되지\s*않(?:았습니다|습니다|음)|"
    r"명시(?:되어)?\s*있지\s*않(?:았습니다|습니다|음)|미확인|확인\s*필요|정보\s*없음|미기재|n/?a|unknown)",
    re.IGNORECASE,
)
_SENTENCES = re.compile(r"(?<=[.!?。])(?:\s+|$)|[\r\n]+")
_PURPOSE = re.compile(
    r"(?:[0-9]+[.\-]?\s*)?사업\s*목적\s*(?:[○ㅇ□●▪▶※*\-]+\s*)?"
    r"(.+?)(?=(?:[0-9]+[.\-]?\s*)?(?:사업\s*(?:내용|구분)|지원\s*(?:규모|내용|분야|대상)|"
    r"사업비|추진\s*(?:방향|체계)|신청\s*자격))"
)


def clean_search_text(value: str | None) -> str:
    if not value:
        return ""
    sentences = _SENTENCES.split(unicodedata.normalize("NFKC", value))
    normalized = (re.sub(r"\s+", " ", text).strip().lstrip(".!?。 ") for text in sentences)
    return " ".join(
        text for text in normalized if text and not _UNKNOWN.fullmatch(text.rstrip(".!?。 "))
    )


def search_purpose(saved: str | None, content: str, summary: str) -> str:
    purpose = clean_search_text(saved)
    if purpose:
        return purpose[:1000]
    match = _PURPOSE.search(re.sub(r"\s+", " ", content))
    if match and (purpose := clean_search_text(match.group(1))):
        return purpose[:1000]
    return " ".join(_SENTENCES.split(clean_search_text(summary))[:2])[:1000]


def build_search_profile(title: str, content: str, summary: str, comparison) -> str:
    purpose = search_purpose(comparison.purpose if comparison else None, content, summary)
    if not purpose:
        return ""
    fields = [("핵심 주제", clean_search_text(title)[:500]), ("사업 목적", purpose)]
    if comparison:
        fields.extend([
            ("지원 대상", clean_search_text(comparison.eligibility)[:500]),
            ("협력 구조", clean_search_text(comparison.required_partner)[:500]),
        ])
    return "\n".join(f"{label}: {value}" for label, value in fields if value)
