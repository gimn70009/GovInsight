"""Limit eligibility evidence to application sections, preserving source wording."""

import re

_EXCLUDED = re.compile(
    r"^(?:지원조건|지원내용|지원규모|지원기간|지원금액|지원비율|지원한도"
    r"|사업비(?:구성|지원|부담|사용|집행|관리)?"
    r"|(?:연구개발과제)?연구개발비(?:구성|지원기준|지원|부담|사용|집행|관리)"
    r"|정부지원연구개발비지원기준|협약(?:체결|기간|및사후관리)?|사후관리|수행중의무"
    r"|제출서류|신청서류|구비서류|선정절차|평가방법|평가기준|평가및선정|선정평가)"
    r"(?:[)）]?[:：].*|[)）]?|[（(].*[)）])$"
)
_NUMBER = r"(?:\d+(?:-\d+)*[.)]\s*|\d+(?:-\d+)*\s+|[Ⅰ-ⅩIVX]+[.)]?\s+)"
_NAMED = re.compile(
    r"(?:신청|지원|참여|모집)(?:자격|대상|요건|제외|제외대상|제한)(?:[:：].*)?"
    r"|사업개요|사업목적|사업내용|신청방법|접수방법|신청기간|추진일정|문의처|기타사항"
)


def application_source_text(text: str) -> str:
    """A nested '신청대상' does not escape an enclosing support/agreement heading."""
    retained: list[str] = []
    excluded_level: int | None = None
    pending_number = False
    established_levels: set[int] = set()
    for raw in re.split(r"\r?\n|(?=[□■]\s*[가-힣][가-힣 \t·/]{1,24}[:：])", text):
        line = raw.strip()
        if line.startswith("[파일:"):
            excluded_level = None
            pending_number = False
            established_levels.clear()
            retained.append(raw)
            continue
        if re.fullmatch(r"\d{1,2}(?:-\d+)?", line):
            pending_number = True
            if excluded_level is None:
                retained.append(raw)
            continue
        split_number = pending_number and len(line) < 60 and not re.match(r"[□■○◦●ㅇ*※-]", line)
        if line:
            pending_number = False
        title = re.sub(r"^" + _NUMBER, "", line)
        title = re.sub(r"^(?:[가-하][.)]|[□■○◦●ㅇ▶▷])\s*", "", title)
        title = re.sub(r"\s+", "", title).lstrip("<〈(（").rstrip(">〉")
        excluded = bool(
            _EXCLUDED.fullmatch(title) or re.fullmatch(r"기술료.*(?:감면|납부유예).*", title)
        )
        numbered = split_number or bool(re.match(_NUMBER, line))
        letter = bool(re.match(r"[가-하][.)]\s*", line))
        square = bool(re.match(r"[□■▶▷]", line))
        bullet = bool(re.match(r"[○◦●ㅇ]\s+", line))
        if excluded or numbered or letter or square or _NAMED.fullmatch(title):
            level = (15 if re.match(r"\d+-", line) else 10) if numbered else (
                20 if letter else 30 if square else 40 if bullet else 30
            )
            if excluded_level is not None and (
                level == excluded_level or (level < excluded_level and level in established_levels)
            ):
                excluded_level = None
            if excluded_level is None:
                established_levels.add(level)
            if excluded and excluded_level is None:
                excluded_level = level
        if excluded_level is None:
            retained.append(raw)
    return "\n".join(retained)