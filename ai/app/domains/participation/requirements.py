"""Structural requirement cues for retaining original context, never for generating facts."""

import re

ROLE_HEADING = re.compile(
    r"^[\s◦○●•ㅇ*-]*\((?:공동)?(?:주관|참여|수행|운영)\s*(?:기관|기업)\)\s*\S"
)
ADMISSION_EFFECT = re.compile(
    r"(?:선정|평가|심사|지원|신청|참여)(?:\s*(?:평가|심사|대상|자격|절차))?"
    r"\s*(?:에서|에|이|을|를)?\s*(?:제외|제한|불가|취소)"
    r"|자동\s*탈락"
)
# These expressions retain evidence for the model. They do not decide eligibility.
CONDITION = re.compile(
    r"(?:신청|참여|수행)[^\n]{0,65}(?:가능|불가|필수|한해|한하여|선택|제외|제한)"
    r"|지원[^\n]{0,45}(?:대상\s*제외|이후|한해|한하여)"
    r"|(?:인력|직원|조직|시설|공간|경력|근무|고용)[^\n]{0,80}(?:이상|이하|필수|자율|확보|구성|원칙|소재지)"
    r"|(?:기관|기업|단체|투자사|운영사)[^\n]{0,50}(?:컨소시엄|필수|역량|결격|지정)"
    r"|(?:컨소시엄|추진단)[^\n]{0,50}(?:필수|구성|확보)"
    r"|(?:선정|완료|성공\s*판정)[^\n]{0,35}(?:이후|기업\s*대상|과제를\s*선발)"
    r"|(?:기업|기관|신청자|참여자|과제)[^\n]{0,100}(?:사전\s*준비|우선\s*지원|선행|먼저)[^\n]{0,60}(?:후|이후|완료|거쳐)"
    r"|(?:참여|선정|희망)\s*기업"
    r"|(?:기|이미)\s*선정[^\n]{0,60}(?:계속|수행)"
    r"|(?:유형|분야)[^\n]{0,45}(?:하나를?\s*선택|택\s*1|선택하여\s*신청)"
    r"|(?:종료평가|성공\s*판정|완료)[^\n]{0,45}(?:선발|이후\s*지원)"
    r"|(?:기업|기관|신청자|참여자)[^\n]{0,45}(?:한함|제외|불가)"
)
PROCEDURE = re.compile(
    r"(?:온라인|누리집|홈페이지|회원가입|계정|로그인|접수|제출|업로드|전산등록)"
)
ADMIN_HEADING = re.compile(
    r"^(?:[□■]\s*)?\(?(?:신청|접수)\s*방법|제출\s*서류|유의\s*사항"
)
SECTION = re.compile(
    r"^(?:[0-9]+[.)]?\s*)?(?:신청\s*요건|신청\s*자격|지원\s*사항|"
    r"(?:모집|사업|과업|평가[·ㆍ]?선정)\s*개요|유의\s*사항|신청\s*방법)$"
)


def condition_context(text):
    """Find substantive conditions even under funding/capacity/precaution headings."""
    for line in text.splitlines():
        if ADMISSION_EFFECT.search(line):
            return True
        if ROLE_HEADING.search(line):
            return True
        if not CONDITION.search(line):
            continue
        # Account and upload instructions remain separate from substantive prerequisites.
        if PROCEDURE.search(line) and not re.search(
            r"(?:지원|참여)\s*(?:제외|제한)|(?:기업|기관|단체).*(?:자격|컨소시엄|결격)", line
        ):
            continue
        return True
    return False


def section_start(line, previous):
    title = line.strip()
    return bool(re.fullmatch(r"\s*\d{1,2}\s*", previous) and (
        SECTION.fullmatch(title) or re.fullmatch(
            r"[가-힣·ㆍ\s]{1,35}(?:자격|요건|대상|기준|사항|체계|일정|목적|개요|방법|규모|내용|기간|서류)"
            r"(?:\s*\([^\n]{1,30}\))?", title
        )
    ))


def administrative_scope(value):
    return bool(ADMIN_HEADING.search(value or ""))


def context_excerpt(text):
    """Retain complete paragraphs, attached notes and tables, not arbitrary character slices."""
    # Role definitions share tables and regional/organizational definitions. A verb-only
    # paragraph filter cannot safely separate eligibility from those dependencies.
    if any(ROLE_HEADING.search(line) for line in text.splitlines()):
        return text
    lines = text.splitlines(keepends=True)
    starts, offset, table = [0], 0, False
    for line in lines:
        # Keep subordinate prerequisites and notes with their parent paragraph.
        if not table and re.match(r"^[ \t]*[◦○●•ㅇ❶-❿①-⑳]", line):
            starts.append(offset)
        if line.strip() == "[표]":
            table = True
        elif line.strip() == "[/표]":
            table = False
        offset += len(line)
    starts = sorted({*starts, len(text)})
    kept = []
    for start, end in zip(starts, starts[1:]):
        block = text[start:end]
        if not condition_context(block):
            continue
        # A following note belongs to this paragraph; retain its entire original span.
        if kept and kept[-1][1] == start:
            kept[-1] = (kept[-1][0], end)
        else:
            kept.append((start, end))
    if not kept:
        return text
    if kept[0][0] > 0:
        prefix = text[:kept[0][0]]
        # Preserve a short heading, but not a long unrelated funding/schedule table.
        if len(prefix.encode()) <= 300:
            kept[0] = (0, kept[0][1])
    return "\n[다음 원문 구간]\n".join(text[a:b] for a, b in kept)
