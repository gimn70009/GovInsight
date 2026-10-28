"""Read explicitly listed documents inside one cell, preserving row and item conditions."""

import re
from dataclasses import dataclass

DOCUMENT = (
    r"체크리스트|계획서|신청서|공적조서|동의서|확인서|증명서|증명원|확약서|서약서|추천서|"
    r"제안서|보고서|재무제표|등록증|등기부등본|등본|초본|증빙서류|증빙자료|증빙서|"
    r"이력서|공문|발표자료|계약서|협약서|검토서|신고증"
)
BULLET = re.compile(r"(?:^|(?<=\s)|(?<=⏎))(?:[-–—•▪◦●○·]|[①-⑳]|\d{1,2}[.)])\s*")
NEGATIVE = re.compile(r"제출\s*(?:불필요|면제|생략)|제출하지|미제출|대체|대신")
CONDITIONAL = re.compile(r"해당|경우|한함|한하여|한해|필요\s*시|기관만|기업만|과제만|또는|택\s*1")
STAGES = (
    (r"(?:협약|계약)\s*(?:시|시점|체결|단계|서류)", "협약 시"),
    (r"선정\s*(?:후|이후)", "선정 후"),
    (r"평가\s*(?:시|단계|서류)", "평가 시"),
)
NAME_HEADER = re.compile(r"서류명|(?:제출|신청|접수|구비)(?:서류|자료)(?:목록|명)?")
GROUP_HEADER = re.compile(r"구분|대상|제출대상|제출조건|조건|필수여부|제출여부|제출구분|필수선택")
GROUP = re.compile(r"공통|필수(?:제출)?|선택(?:제출)?|해당시(?:제출)?|"
                   r".*(?:기관|기업|법인|개인|신청자|사업자|연구|과제|대상)(?:만)?(?:제출|의경우)?")
FORM = re.compile(r"작성\s*예시|작성\s*견본|양식\s*목록|첨부\s*목록|참고\s*자료")


@dataclass(frozen=True)
class CellItem:
    title: str
    quote: str
    condition: str
    context: str


@dataclass(frozen=True)
class GroupedSubmissionRow:
    title: str
    quote: str
    context: str
    marker: str
    condition: str
    grouped: bool = True


def cell_items(cell: str) -> list[CellItem]:
    # Explicit paragraphs and list markers only; punctuation inside names and OR stay intact.
    boundaries = sorted({0, len(cell), *(m.start() for m in BULLET.finditer(cell)),
                         *(m.end() for m in re.finditer("⏎", cell))})
    parts = [cell[a:b].strip(" ⏎") for a, b in zip(boundaries, boundaries[1:])]
    result = []
    parent = ""
    for part in filter(None, parts):
        if not part.lstrip().startswith(("·", "※")):
            parent = ""
        note = part.split("※", 1)[1].strip() if "※" in part else ""
        value = BULLET.sub("", part.split("※", 1)[0], count=1).strip()
        if not value:
            if result and note:
                old = result.pop()
                start = cell.find(old.quote)
                end = cell.find(part, start) + len(part)
                result.append(CellItem(old.title, cell[start:end],
                                       " / ".join(filter(None, [old.condition, note])),
                                       old.context + " " + note))
            continue
        # A subordinate named file inherits its explicit group condition.
        if re.fullmatch(r".{1,70}(?:관련\s*)?서류\s*[(（][^()（）]+[)）]", value):
            parent = value
            continue
        title = value if re.search(r"또는|택\s*1", value) else re.split(
            r"\s+(?:원본|사본)(?=\s|$)|\s+(?:각\s*)?\d+\s*(?:부|매)(?=\W|$)|[:：]", value, 1,
        )[0]
        title = re.sub(r"^[(（]해당\s*시[)）]\s*", "", title).strip()
        title = re.sub(r"\s*[(（]해당\s*시[)）]$", "", title)
        if len(title) > 150 or not re.search(
            rf"(?:{DOCUMENT})(?=$|\s|[(（])", title,
        ):
            # A wrapped continuation/annotation belongs to the previous item; do not drop it.
            if result and not BULLET.match(part) and not result[-1].quote.endswith(part):
                old = result.pop()
                quote = cell[cell.find(old.quote):cell.find(part, cell.find(old.quote)) + len(part)]
                result.append(CellItem(old.title, quote, old.condition, old.context + " " + part))
            continue
        conditions = [p for p in (parent, value if CONDITIONAL.search(value) else "", note) if p]
        result.append(CellItem(title, part, " / ".join(conditions), value + " " + note))
        if not part.lstrip().startswith("·"):
            parent = ""
    return result


def grouped_rows(rows: list[list[str]], context: str, after: str):
    """None means the legacy single-document reader should handle this table."""
    if len(rows) < 2 or FORM.search(context):
        return None
    headers = [re.sub(r"\s+", "", c) for c in rows[0]]
    names = [i for i, h in enumerate(headers) if NAME_HEADER.fullmatch(h)]
    groups = [i for i, h in enumerate(headers) if GROUP_HEADER.fullmatch(h)]
    if len(names) != 1 or len(groups) != 1:
        return None
    name, group = names[0], groups[0]
    if name == group or not any(
        len(cell_items(row[name])) > 1 for row in rows[1:]
    ):
        return None
    if not headers[name].startswith("제출") and not re.search(
        r"(?:제출|신청|접수|구비)\s*(?:서류|자료)", context,
    ):
        return None
    # Never infer exceptions from an incomplete table or an adjacent common exemption.
    if re.match(r"\s*[*※][^\n]*(?:면제|생략|미제출|대체|대신)", after):
        return []
    result = []
    for row in rows[1:]:
        target = row[group].strip()
        compact = re.sub(r"\s+", "", target)
        if not GROUP.fullmatch(compact) or NEGATIVE.search(target):
            continue
        extras = [value.strip() for i, value in enumerate(row)
                  if i not in (name, group) and value.strip()]
        shared = [note.strip() for note in re.findall(r"※([^⏎]+)", row[name])
                  if re.search(r"모두|공통|각각", note)]
        for item in cell_items(row[name]):
            condition = " / ".join(dict.fromkeys(
                p for p in [target if compact not in ("공통", "필수", "필수제출") else "",
                            *item.condition.split(" / "), *extras, *shared] if p
            ))
            if NEGATIVE.search(item.quote + " " + condition):
                continue
            if len(item.quote) > 300 or len(condition) > 200:
                continue
            local = item.context + " " + " ".join(extras)
            stage = next((label for pattern, label in STAGES if re.search(pattern, local)), None)
            if stage is None:
                stage = next(
                    (label for pattern, label in STAGES if re.search(pattern, context)), None,
                )
            if stage is None and re.search(r"신청|접수", context):
                stage = "신청서류"
            if stage is None:
                continue
            marker = "선택" if compact.startswith("선택") else (
                "해당 시" if compact not in ("공통", "필수", "필수제출")
                or CONDITIONAL.search(condition) else "필수"
            )
            # Keep quantity, nested notes and alternatives verbatim as the evidence.
            result.append(GroupedSubmissionRow(item.title, item.quote, stage, marker, condition))
    return result
