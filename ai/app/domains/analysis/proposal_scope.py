"""Reject clearly administrative documents before any drafting or cache reuse."""

import re

_ADMIN = re.compile(
    r"(?:확인서|동의서|확약서|서약서|증명서|자가\s*진단(?:표)?|체크리스트)\s*$|"
    r"\b(?:consent form|declaration|certificate|checklist)\s*$",
    re.I,
)
_NOTICE = re.compile(
    r"(?:공고|공고문)\s*$|공고\s*제\s*\d|\b(?:call for applications|funding announcement)\s*$", re.I
)
_FORM = re.compile(
    r"(?:사업\s*계획서|연구\s*개발\s*계획서|제안서)(?:\s*[\[(].*[\])])?\s*$|"
    r"\b(?:business plan|research proposal|executive summary of application)\s*$",
    re.I,
)
_TOPIC = re.compile(
    r"사업|제품|서비스|협력|연구|개발|추진|목표|계획|성과|역량|현황|활용|필요성|시장"
)
_WRITE = re.compile(
    r"(?:작성|기재|서술|기술|설명)\s*(?:해\s*주|해주|하십|하세|합|할\s*것|바람|요망)|"
    r"(?:등|내용|계획|현황|상세히|구체적으로|이내로|이내)\s*(?:작성|기재|서술)\s*[)）]?$"
)
_PLAN_FIELD = re.compile(
    r"(?:사업\s*목표|사업\s*내용|추진\s*계획|기대\s*효과|연구\s*목표|"
    r"project objectives|implementation plan)",
    re.I,
)
_ADMIN_PROMPT = re.compile(
    r"해당\s*여부|동의|날인|서명|체납|확인서|증빙|신청\s*자격|제출\s*(?:목록|서류)"
)


def drafting_exclusion(text: str) -> str:
    # Only short source lines are headings/prompts, not flattened whole-table cells.
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    short = [line for line in lines if len(line) <= 320]
    # Mixed attachments may contain a real plan after a notice or consent page.
    if any(
        not _ADMIN_PROMPT.search(line)
        and (
            (_TOPIC.search(line) and _WRITE.search(line))
            or re.search(
                r"\b(?:describe|explain)\b.+\b(?:business|project|research|cooperation|products?)\b",
                line,
                re.I,
            )
        )
        for line in short
    ) or any(
        len(line) <= 120
        and _FORM.search(line)
        and any(
            _PLAN_FIELD.fullmatch(child.strip(" #□ㅇ0123456789.-()"))
            for child in lines[index + 1 : index + 5]
        )
        for index, line in enumerate(lines)
    ):
        return ""
    headers = [line for line in lines[:24] if len(line) <= 160]
    if any(_NOTICE.search(line) for line in headers):
        return (
            "공고·안내 문서는 초안 작성 대상이 아닙니다. 신청서나 사업계획서 양식을 선택해 주세요."
        )
    if any(_ADMIN.search(line) for line in headers):
        return (
            "확인서·동의서·체크리스트는 초안 작성 대상이 아닙니다. "
            "해당 여부 확인과 서명은 직접 작성해 주세요."
        )
    return ""
