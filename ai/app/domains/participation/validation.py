import re
from difflib import SequenceMatcher

from .preprocess import clean
from .schemas import Draft, RewriteRequest

BAD = re.compile(
    r"[①-⑳□■※*\ufffd]|\[참고\s*\d+\]|<|>"
    r"|(?:^|\s)(?:[가나다라마바사아자차카타파하]|\d+)\.\s|제\s*\d+\s*(?:조|항|호)"
    r"|(?:^|\s)(?:[가나다라마바사아자차카타파하]|\d+)[.)](?:\s|$)|(?:^|\s)\(\d+\)|^[ㆍ・○◦●•-]|[ㆍ・]{2,}"
    r"|[가-힣]{20,}|기준은|가능할 수|사항\s*:|\.{3}|…"
)
END = re.compile(r"(?:입니다|합니다|됩니다|있습니다|없습니다)\.$")
FRAGMENT = re.compile(
    r"^(?:제품|민간기관|정책적\s*배려기업\s*소상공인,?\s*소기업)이?\s*대상입니다\.$"
)
NUMBERS = re.compile(r"\d[\d,]*(?:\.\d+)?")
ORGANIZATIONS = re.compile(
    r"[가-힣A-Za-z]{2,}(?:진흥원|공단|공사|재단|협회|대학교|연구원|기술원|부처)"
)


def key(value: str) -> str:
    return re.sub(r"[\s,·ㆍ『』「」]", "", value)


def validate(
    draft: Draft, request: RewriteRequest, types: list[str], attempt: int
) -> dict[int, list[str]]:
    sources = {source.id: source.excerpt for source in request.sources}
    codes = {criterion.code for criterion in request.criteria}
    errors: dict[int, list[str]] = {}

    def fail(index: int, reason: str):
        errors.setdefault(index, []).append(reason)

    for index, item in enumerate(draft.statements):
        text = item.text.strip()
        evidence = "\n".join(sources.get(sid, "") for sid in item.source_ids)
        if not item.source_ids or any(sid not in sources for sid in item.source_ids):
            fail(index, "unknown_source")
        if item.source_ids and all(
            FRAGMENT.fullmatch(clean(sources.get(sid, "")))
            or re.fullmatch(r"(?:업력 기준은 \d+년 이상입니다\.\s*)+", sources.get(sid, ""))
            for sid in item.source_ids
        ):
            fail(index, "source_subject_unrecoverable")
        if item.criterion not in codes:
            fail(index, "wrong_criterion")
        if len(text) < 10 or not END.search(text) or BAD.search(text) or FRAGMENT.fullmatch(text):
            fail(index, "incomplete_or_mechanical_sentence")
        if len(re.findall(r"(?:입니다|합니다|됩니다|있습니다|없습니다)\.", text)) > 1 or re.search(
            r"^(?:이는|이 우대|해당 고용 실적|해당 요건)", text
        ):
            fail(index, "dependent_fragment")
        if not item.supporting_quotes or any(
            not key(quote) or key(quote) not in key(evidence) for quote in item.supporting_quotes
        ):
            fail(
                index,
                "unsupported_quote: 인용은 원문의 연속 구절만 그대로 복사하세요. "
                "생략부호로 이어 붙이지 마세요",
            )
        supported = "\n".join(item.supporting_quotes) + " " + item.group_label
        original_numbers = {number.replace(",", "") for number in NUMBERS.findall(supported)}
        if any(number.replace(",", "") not in original_numbers for number in NUMBERS.findall(text)):
            fail(index, "unsupported_number")
        if any(key(name) not in key(supported) for name in ORGANIZATIONS.findall(text)):
            fail(index, "unsupported_organization")
        group_parts = re.split(r"[,/]\s+|\s+(?:및|와|과)\s+", item.group_label)
        grounded_groups = len(group_parts) > 1 and all(
            key(part) in {key(name) for name in types} and key(part) in key(clean(evidence))
            for part in group_parts
        )
        if (
            not grounded_groups
            and item.group_label not in {"", "모든 유형 공통", "모든 유형 공통으로"}
            and key(item.group_label) not in key(evidence)
            and key(item.group_label) not in key(clean(evidence))
        ):
            fail(index, "unsupported_group")
        if (
            len(types) > 1
            and "모든 유형 공통으로" not in text
            and not any(key(name) in key(text) for name in types)
        ):
            fail(index, "missing_type: 문장 앞에 적용 유형명 또는 모든 유형 공통으로를 명시하세요")
        if item.criterion == "COMPANY_SIZE" and re.search(
            r"(?:업력|설립|매출|창업\s*후).*\d", text
        ):
            fail(index, "wrong_criterion")
        if item.criterion == "REPRESENTATIVE" and re.search(
            r"참여\s*제한|보조금법|부정\s*수급", text
        ):
            fail(index, "wrong_criterion")
        if re.search(r"제\s*\d+\s*(?:조|항|호)", text):
            fail(index, "legal_article_number: 법령명만 남기고 제N조/항/호는 삭제하세요")
        if item.kind in {"PREFERENCE", "DEDUCTION"} and not item.criterion.startswith("PREF_"):
            fail(index, "wrong_criterion: 우대/가점은 PREF_ 항목에만 분류하세요")
        if re.search(r"(?:최대\s*\d+점|기업|제품)\s*유형", text) and not any(
            key(name) in key(text) for name in types
        ):
            fail(index, "invented_type: 점수와 기업/제품 소제목을 사업 유형으로 바꾸지 마세요")
        preference_only = all(
            re.match(r"^\s*\(최대\s*\d+점\)", sources.get(sid, "")) for sid in item.source_ids
        )
        if preference_only and not item.criterion.startswith("PREF_"):
            fail(index, "preference_not_mandatory: 가점 조건을 필수 신청요건으로 바꾸지 마세요")
        if (
            preference_only
            and re.search(r"정책적\s*배려기업|소상공인|소기업", text)
            and re.search(r"정책적\s*배려기업\s*소상공인,?\s*소기업이\s*대상입니다", evidence)
        ):
            fail(
                index, "orphan_table_cell: 뒤섞인 별도 표의 대상 조각을 가점 대상에 연결하지 마세요"
            )
        if item.criterion == "SANCTIONS" and re.search(
            r"요구하지\s*않|영향력을?\s*행사|사업\s*목적", text
        ):
            fail(index, "wrong_criterion: 기관 소개는 제외하고 제재 조건만 작성하세요")
        condition = text.replace(item.group_label, "") if item.group_label else text
        age_fact = re.search(r"(?:업력|설립|창업)\s*(?:후|이|가)?\s*\d+\s*년", condition)
        revenue_fact = re.search(r"매출(?:액)?\s*(?:이|가|은|는)?\s*\d+", condition)
        if (
            item.criterion == "REVENUE"
            and age_fact
            or item.criterion == "BUSINESS_AGE"
            and revenue_fact
            or item.criterion == "COMPANY_SIZE"
            and (age_fact or revenue_fact)
        ):
            fail(index, "wrong_criterion")
        for previous, other in enumerate(draft.statements[:index]):
            identical = key(text) == key(other.text)
            near = (
                item.criterion == other.criterion
                and SequenceMatcher(None, key(text), key(other.text)).ratio() >= 0.9
            )
            # Different, source-grounded tracks may remain after a second semantic review.
            distinct_groups = (
                item.group_label
                and other.group_label
                and item.group_label != other.group_label
                and key(item.group_label) in key(text)
                and key(other.group_label) in key(other.text)
            )
            if identical or (near and (attempt == 1 or not distinct_groups)):
                fail(index, "duplicate_or_lost_group")
                fail(previous, "duplicate_or_lost_group")
    return errors
