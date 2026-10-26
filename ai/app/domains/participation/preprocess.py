import re

from .schemas import PreparedRow, RewriteRequest

PREFIX = re.compile(
    r"^(?:(?:[가나다라마바사아자차카타파하]|\d+)[.)](?!\d)|\(\d+\)|[①-⑳□■○◦●※*ㆍ・•-])\s*"
)
SUFFIX = re.compile(r"\s+(?:[가나다라마바사아자차카타파하][.)]|\d+[.)]|[①-⑳□※*])\s*$")
REFERENCE = re.compile(
    r"\([^()]*(?:\[참고\s*\d+\]|\d+\s*p\s+[^()]*참조)[^()]*\)"
    r"|\[참고\s*\d+\]|<\s*별표\s*\d+\s*>"
)
GROUP = re.compile(
    r"(?:스케일업\s*)?[가-힣A-Za-z0-9,]+(?:\s+[0-9,]+억)?\s*트랙|[가-힣A-Za-z0-9]+과제|유형\s*[0-9A-Za-zⅠ-Ⅹ]+|안전환경\s*\((?:일반|고위험업종)\)"
)
HEADER = re.compile(
    r"구분|지원유형|지원한도|주요내용|제출서류|비고|지원규모|자부담|국비|세부지원내용"
    r"|업종|업종산재보험가입코드|신청조건|기관부담비율|연구개발기관유형"
)


GENERIC_TYPES = {
    "연구개발과제",
    "국가연구개발과제",
    "지원과제",
    "신청과제",
    "해당과제",
    "개별과제",
    "사업과제",
    "수행과제",
    "선정과제",
    "대상과제",
    "신규과제",
    "중복과제",
    "유사과제",
    "정부지원과제",
    "안전과제",
    "위탁과제",
    "보안과제",
}


def specific_type(name: str) -> bool:
    return name.replace(" ", "") not in GENERIC_TYPES and bool(GROUP.fullmatch(name))


def clean(text: str) -> str:
    text = text.replace("\ufffd", " ").replace("\ufeff", "")
    text = re.sub(r"\(\s*기\s+업\s*\)", "(기업)", text)
    text = re.sub(r"\(\s*제\s+품\s*\)", "(제품)", text)
    text = REFERENCE.sub(" ", text)
    while PREFIX.match(text):
        text = PREFIX.sub("", text, count=1)
    text = SUFFIX.sub("", text)
    text = re.sub(r"[□■○※*①-⑳]|[ㆍ・]{2,}", " ", text)
    text = re.sub(r"^사항\s*[:：]\s*", "", text)
    return re.sub(r"\s+", " ", text).strip()


def preprocess(request: RewriteRequest) -> tuple[list[PreparedRow], list[str]]:
    catalog = {item.code: item for item in request.criteria}
    rows: list[PreparedRow] = []
    types = list(dict.fromkeys(request.business_types))
    for source in request.sources:
        group = clean(source.group_label)
        headers: list[str] = []
        seen: set[tuple[str, str, tuple[str, ...]]] = set()
        # Preserve whole clauses and original evidence; never slice to a character limit.
        for line in re.split(r"\n+|(?=[□■])", source.excerpt):
            original = line.strip(" \r")
            if not original:
                continue
            value = clean(original)
            if not value:
                continue
            if value in {"[표]", "[/표]"}:
                headers = []
                continue
            # Empty cells carry column positions; collapsing them can swap funding amounts.
            raw_cells = (
                original.split("\t")
                if "\t" in original
                else re.split(r"\s*\|\s*", original.strip("|"))
            )
            cells = [clean(cell) for cell in raw_cells]
            is_table = len(cells) >= 2
            header_cells = [re.sub(r"[\s()%0-9,.]", "", cell) for cell in cells]
            if is_table and all(HEADER.fullmatch(cell) for cell in header_cells):
                if len(headers) == len(cells):
                    headers = [
                        old if old == new else old + " / " + new for old, new in zip(headers, cells)
                    ]
                else:
                    headers = cells
                continue
            inline = re.match(r"^\(([^()]{1,60})\)\s*(.+)$", value)
            subtitle = re.match(r"^([^:：]{1,40})[:：]\s*(.+)$", value)
            if inline and inline[1] not in {"기업", "제품"} and not GROUP.fullmatch(group):
                group = inline[1]
            elif subtitle and not GROUP.fullmatch(group):
                group = subtitle[1]
            elif len(value) <= 60 and re.search(r"(?:트랙|과제|유형|프로그램)$", value):
                group = value
                if specific_type(value) and value not in types:
                    types.append(value)
                continue
            if is_table and re.search(r"트랙|과제|유형", cells[0]):
                group = cells[0]
            found_types = [
                found for found in GROUP.finditer(value) if specific_type(found[0].strip())
            ]
            type_overview = len(found_types) > 1 and re.search(
                r"(?:지원|사업|신청|과제)\s*(?:유형|내용)|(?:트랙|프로그램|유형)\s*[:：]", value
            )
            for found in found_types:
                # An incidental reference to a task is not an application track.
                heading_before_subject = re.match(r"\s*\((?:기업|제품)\)", value[found.end() :])
                name = found[0].strip()
                if (type_overview or heading_before_subject) and name not in types:
                    types.append(name)
            if group and specific_type(group) and group not in types:
                types.append(group)
            identity = (value, group, tuple(headers))
            if identity in seen:
                continue
            seen.add(identity)
            ambiguous = not is_table and len(HEADER.findall(value)) >= 3
            rows.append(
                PreparedRow(
                    source_id=source.id,
                    raw_excerpt=original,
                    text=value,
                    group_label=group,
                    headers=headers.copy(),
                    cells=cells if is_table else [],
                    structure="table_row"
                    if is_table
                    else "ambiguous_table"
                    if ambiguous
                    else "text",
                    criteria=[catalog[code] for code in source.criteria if code in catalog]
                    or request.criteria,
                )
            )
    unique_types = {}
    for name in types:
        unique_types.setdefault(re.sub(r"\s+", "", name), name)
    return rows, list(unique_types.values())
