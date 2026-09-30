"""Bounded submission-section evidence shared by proposals and reports."""

import re
import unicodedata

# Only explicit submission sections can lend a heading to a quoted table row.
# Numbered document rows are deliberately not treated as outline headings.
_SECTION_TOPIC = (
    r"(?:제\s*출|구\s*비|신\s*청)\s*서\s*류|문의|연락처|유의\s*사항|"
    r"참고|예시|견본|별첨|붙임|첨부\s*(?:파일|자료)|기타|"
    r"(?:신청|접수|제출)\s*(?:방법|기간|절차|자격|대상)|"
    r"(?:지원|사업)\s*(?:내용|대상|개요|목적)|"
    r"(?:평가|선정)\s*(?:기준|방법|절차|항목|일정|결과|후|이후)|"
    r"협약\s*(?:체결|후|이후|시)|작성\s*(?:방법|요령)|준비\s*사항"
)
_OUTLINE_PREFIX = rf"(?:\d+[.)]\s*|\d+\s+|[가-하][.)]\s*)(?={_SECTION_TOPIC})"
_SECTION_PREFIX = re.compile(rf"^(?:[□■○◦●ㅇ]\s*|{_OUTLINE_PREFIX})")
_SUBMISSION_HEADING = re.compile(
    r"^[\[(]?(?:제\s*출|구\s*비|신\s*청)\s*서\s*류"
    r"(?:[ \t]*목록)?[\])]?(?P<tail>.*)$"
)
_TABLE_START = re.compile(r"^(?:[:：]|구분|서류명|번호|No\.?|필수|\d+[.)]|[-•])", re.I)
_NON_APPLICATION = re.compile(
    r"참고(?:용|자료)|예시|견본|제출(?:불필요|면제|생략)|제출하지않|"
    r"(?:선정|협약)(?:체결)?(?:이후|후|시)|(?:사업|운영)(?:종료|완료)후"
)



def _compact(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).translate(str.maketrans("‘’“”", "''\"\""))
    return re.sub(r"\s+", "", value)



def submission_section_contains(quote: str, text: str) -> bool:
    """Find the entire quote under a bounded, same-source submission heading.

    Restore only structural line breaks lost by HTML/PDF extraction. A heading
    supplies submission intent, never another row's conditions or missing text.
    """
    text = unicodedata.normalize("NFKC", text)
    text = re.sub(r"([□■○◦●])", r"\n\1", text)
    text = re.sub(rf"(?<!\S)({_OUTLINE_PREFIX})", r"\n\1", text)
    scopes: dict[int, bool] = {}
    block: list[str] | None = None
    quoted = _compact(quote)

    def contains() -> bool:
        value = "\n".join(block or [])
        return bool(
            quoted and len(value) <= 2000 and quoted in _compact(value)
            and not _NON_APPLICATION.search(_compact(value))
        )

    for raw in text.splitlines():
        line = raw.strip()
        prefix = _SECTION_PREFIX.match(line)
        label = line[prefix.end():] if prefix else line
        heading = _SUBMISSION_HEADING.match(label)
        tail = heading["tail"].strip() if heading else ""
        if heading and tail and not _TABLE_START.match(tail):
            heading = None
        gap = line.startswith(("[일부 원문 생략", "[파일:"))
        boundary = prefix or heading or re.match(_SECTION_TOPIC, label.lstrip("[<("))
        if not (boundary or gap):
            if block is not None:
                block.append(raw)
            continue
        if contains():
            return True
        block = None
        if gap:
            continue
        if prefix:
            marker = prefix[0].strip()[0]
            level = 1 if marker in "□■" or marker.isdigit() else (
                3 if marker in "○◦●ㅇ" else 2
            )
        else:
            # A bare column header cannot clear a containing reference/stage heading.
            level = 5 if heading else 4
        scopes = {rank: blocked for rank, blocked in scopes.items() if rank < level}
        scopes[level] = bool(
            _NON_APPLICATION.search(_compact(label)) or label.startswith("참고")
        )
        if heading and not any(scopes.values()):
            block = [tail]
    return contains()
