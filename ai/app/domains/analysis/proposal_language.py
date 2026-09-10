"""Conservative writing-language selection from the selected template only."""

import re
from pathlib import PurePosixPath
from typing import Literal

WritingLanguage = Literal["ko", "en"]
_KOREAN = re.compile(r"국문|한글|한국어|\bkorean\b", re.IGNORECASE)
_ENGLISH = re.compile(r"영문(?!명|주소|학)|영어|\benglish\b", re.IGNORECASE)
_WRITE = re.compile(r"작성|기재|\b(?:write|written|complete[d]?|fill(?:ed)?|prepare[d]?)\b", re.I)
_SCOPE = re.compile(
    r"(?:본|이|해당)\s*(?:양식|서식|신청서|요약서|제안서|사업계획서|문서)|"
    r"(?:전체|모든)\s*(?:항목|내용|본문|양식)|"
    r"\b(?:this|the|entire)\s+(?:form|application|proposal|document|summary)\b|"
    r"\ball\s+(?:fields|sections|answers|responses)\b",
    re.I,
)
_FORM_TITLE = re.compile(
    r"양식|서식|신청서|요약서|제안서|계획서|\b(?:form|application|proposal|summary)\b", re.I
)
_OTHER_FILE = re.compile(
    r"별첨|별도|다른\s*(?:양식|파일)|첨부\s*(?:파일|양식)|\b(?:separate|attachment)\b", re.I
)
_LOCAL_FIELD = re.compile(r"기업명|회사명|성명|주소|\b(?:company name|full name|address)\b", re.I)
_NEGATED_PREFIX = re.compile(
    r"\bnot\s+(?:in\s+|using\s+)?$|\b(?:do|must) not (?:use|write in)\s*$", re.I
)
_NEGATED_SUFFIX = re.compile(
    r"^\s*(?:(?:으로|로|은|는|을|를)\s*)?"
    r"(?:(?:작성|기재|사용)(?:은|는|을|를|이|가)?\s*)?"
    r"(?:하지\s*(?:않|마)|금지|불필요|필수(?:가|는)?\s*아(?:니|님|닙)|대신)|"
    r"^\s*(?:(?:is|are)\s+)?(?:not\s+(?:required|necessary|allowed|permitted)|optional)\b",
    re.I,
)


def _affirmative(line: str, marker: re.Pattern) -> bool:
    return any(
        not _NEGATED_PREFIX.search(line[: match.start()])
        and not _NEGATED_SUFFIX.search(line[match.end() :])
        for match in marker.finditer(line)
    )


def _directive(line: str, header: bool) -> WritingLanguage | None:
    if len(line) > 240 or not _WRITE.search(line):
        return None
    scoped = _SCOPE.search(line)
    standalone = re.match(r"^(?:국문|한글|한국어|영문|영어)\s*(?:으로|로)?\s*(?:작성|기재)", line)
    english_instruction = re.match(
        r"^(?:please\s+)?(?:do not\s+)?"
        r"(?:write|complete|fill|prepare|to be (?:written|completed))\b",
        line,
        re.I,
    )
    if not scoped and not (
        header and (standalone or english_instruction or _FORM_TITLE.search(line))
    ):
        return None
    if not scoped and (_OTHER_FILE.search(line) or _LOCAL_FIELD.search(line)):
        return None
    if _affirmative(line, _KOREAN):
        return "ko"
    if _affirmative(line, _ENGLISH):
        return "en"
    # A negated/optional English requirement keeps the Korean default.
    return "ko" if _ENGLISH.search(line) else None


def template_writing_language(file_name: str, template_text: str) -> WritingLanguage:
    name = PurePosixPath(file_name.replace("\\", "/")).stem
    lines = [line.strip(" \t※*#•[]()") for line in template_text.splitlines() if line.strip()]
    directives = {_directive(line, index < 3) for index, line in enumerate(lines)}
    if "ko" in directives or _KOREAN.search(name):
        return "ko"
    if "en" in directives:
        return "en"
    if re.search(
        r"영문(?!명|주소|학)|\benglish\b|\(영어\)|영어\s*(?:양식|서식|버전|요약)", name, re.I
    ):
        return "en"
    # Acronyms and English field labels in Korean forms are not language requirements.
    # Mixed or unclear forms keep the established Korean behavior.
    if (
        not re.search(r"[가-힣ㄱ-ㅎㅏ-ㅣ]", template_text)
        and len(re.findall(r"[A-Za-z]{2,}", template_text)) >= 4
    ):
        return "en"
    return "ko"


class EnglishBodyFormatError(ValueError):
    def __init__(self, paragraph_number: int, paragraph: str):
        super().__init__("영문 본문은 문장이 완결된 문단과 종결 부호로 작성해야 합니다.")
        self.paragraph_number = paragraph_number
        self.section_id = None
        tail = paragraph[-200:]
        if re.search(
            r"[\[(]\s*(?:(?:company[ _-]*)?evidence|source[ _-]*ids?|근거|출처)", tail, re.I
        ):
            self.ending_kind = "trailing_reference"
        elif _unfinished_tail(paragraph):
            self.ending_kind = "unfinished_clause"
        elif re.search(r"[\]\)]$", paragraph):
            self.ending_kind = "closing_bracket"
        elif re.search(r"[*_`]$", paragraph):
            self.ending_kind = "markdown"
        elif paragraph[-1:].isalpha() or paragraph[-1:].isdigit():
            self.ending_kind = "missing_terminal_or_wrapped_line"
        elif paragraph[-1:] in {",", ":", ";", "-", "—"}:
            self.ending_kind = "unfinished_clause"
        else:
            self.ending_kind = "other_formatting"

    def safe_hint(self):
        return (
            f"{self} section={self.section_id} paragraph={self.paragraph_number} "
            f"ending={self.ending_kind}"
        )


_TERMINAL = re.compile(r"[.!?][\"'’”)\]]*$")
_DANGLING_TAIL = re.compile(
    r"\b(?:and|or|but|because|although|whereas|including|such as|the|a|an|our|their|its)$",
    re.I,
)


def _unfinished_tail(paragraph: str) -> bool:
    content = re.sub(r"[.!?\"'’”)\]]+$", "", paragraph).strip()
    return bool(_DANGLING_TAIL.search(content) or re.search(r"\.{2,}$", paragraph))


def normalize_english_body(body: str, title: str = "") -> str:
    """Repair presentation only; do not invent words or rewrite company facts."""
    body = re.sub(r"[\u200b\ufeff\u2060]", "", body)
    body = body.replace("\r\n", "\n").replace("\r", "\n")
    body = body.replace("\u2028", "\n").replace("\u2029", "\n\n")
    fence = re.fullmatch(r"\s*```(?:text|plaintext|english)?\s*\n(.*?)\n```\s*", body, re.S | re.I)
    if fence:
        body = fence[1]
    title_key = re.sub(r"\s+", " ", title).strip().casefold().rstrip(":")
    lines = []
    for line in body.split("\n"):
        line = re.sub(r"(\*\*|__)(\S(?:.*?\S)?)\1", r"\2", line.strip())
        line = re.sub(r"`([^`]+)`", r"\1", line)
        line = re.sub(r"(?<!\w)([*_])(\S(?:.*?\S)?)\1(?!\w)", r"\2", line)
        plain = re.sub(r"^#{1,6}\s+", "", line)
        if title_key and re.sub(r"\s+", " ", plain).casefold().rstrip(":") == title_key:
            continue
        # Unknown headings or unfinished list labels need a model repair, not an added period.
        if re.match(r"^#{1,6}\s|^```", line):
            blocks = re.split(r"\n\s*\n", "\n".join([*lines, line]))
            raise EnglishBodyFormatError(sum(bool(block.strip()) for block in blocks), line)
        line = re.sub(r"^(?:[-*•]|\d{1,2}[.)])\s+", "", line)
        lines.append(line)
    paragraphs = []
    for block in re.split(r"\n\s*\n", "\n".join(lines)):
        paragraph = " ".join(line.strip() for line in block.splitlines() if line.strip())
        if not paragraph:
            continue
        paragraph = re.sub(
            r"[。．！？](?=[\"'’”)\]]*$)",
            lambda m: {
                "。": ".",
                "．": ".",
                "！": "!",
                "？": "?",
            }[m[0]],
            paragraph,
        )
        if not _TERMINAL.search(paragraph) and not _unfinished_tail(paragraph):
            content = re.sub(r"[\"'’”)\]]+$", "", paragraph).rstrip()
            words = re.findall(r"\b[A-Za-z]+\b", content)
            sentence_like = len(words) >= 8 or (
                len(words) >= 2
                and re.search(
                    r"\b(?:we|our company|is|are|has|have|will|can|should|must)\b", content, re.I
                )
            )
            if sentence_like and content[-1:].isalnum():
                paragraph += "."
        paragraphs.append(paragraph)
    return "\n\n".join(paragraphs)


def verify_english_body(body: str) -> None:
    paragraphs = [line.strip() for line in body.splitlines() if line.strip()]
    for paragraph_number, paragraph in enumerate(paragraphs, 1):
        letters = [char for char in paragraph if char.isalpha()]
        latin = sum("a" <= char.lower() <= "z" for char in letters)
        if not letters or latin / len(letters) < 0.9 or re.search(r"[가-힣]니다[.!?]", paragraph):
            raise ValueError("영문 양식의 본문은 영어로 작성해야 합니다.")
        # Check paragraph endings, not abbreviation boundaries such as U.S., Inc. or e.g.
        if not _TERMINAL.search(paragraph) or _unfinished_tail(paragraph):
            raise EnglishBodyFormatError(paragraph_number, paragraph)
    if not paragraphs:
        raise ValueError("영문 본문을 작성해야 합니다.")
    if re.search(
        r"\b(?:company_evidence_ids|evidence_ids)\b|"
        r"[\[(]\s*(?:(?:company[ _-]*)?evidence(?:[ _-]*ids?)?|source[ _-]*ids?)\s*[:=]|"
        r"\bplease\s+(?:write|describe|enter|fill|insert|provide)\b|"
        r"\byou\s+(?:should|must|need to)\b|\byour company\b|"
        r"\[(?:insert|enter|company name|to be (?:confirmed|completed))[^]\n]*\]",
        body,
        re.I,
    ):
        raise ValueError("영문 작성 안내나 빈칸 대신 회사의 제안 본문을 작성해야 합니다.")
