"""Conservative checks for demonstrably mismatched evidence, not general entailment."""

import re

DOCUMENT = re.compile(
    r"법인설립허가증|비영리민간단체등록증|사업자등록증(?:명원)?|고유번호증"
    r"|법인등기부등본|법인등기사항증명서|서약서|동의서|납세증명서|인감증명서"
)
PROCUREMENT = re.compile(r"우선조달계약|우선구매|계약방법|입찰방식|수의계약")
DENIAL = re.compile(
    r"(?:신청|참여|지원|입찰참가)(?:할수없|불가|불가능|제한|제외|자격이없|할수없는)"
)
LIST_HEADING = re.compile(r"신청서류|제출서류|구비서류")
QUALIFICATION = re.compile(
    r"(?:등록|허가|인가|지정)(?:을받|받|된|한)|자격|참여제한|등록되어|등록돼"
)


def rejection(text, quote, kind, heading="", *, detail=False):
    text, quote, heading = (re.sub(r"\s+", "", value) for value in (text, quote, heading))
    if any(document not in quote for document in DOCUMENT.findall(text)):
        return "DOCUMENT_QUOTE_MISMATCH"
    if detail:
        return None
    if (
        kind == "EXCLUSION"
        and PROCUREMENT.search(quote)
        and "예외" in quote
        and not DENIAL.search(quote)
    ):
        return "PROCUREMENT_EXCEPTION"
    if (
        kind == "ELIGIBILITY"
        and LIST_HEADING.search(heading)
        and DOCUMENT.search(quote)
        and not QUALIFICATION.search(quote)
        and not DENIAL.search(quote)
    ):
        return "DOCUMENT_LIST_NOT_QUALIFICATION"
    return None
