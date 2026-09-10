"""Conservative presentation repair for Korean proposal prose."""

import re

_ENDING = re.compile(r"[가-힣]니다(?:\s*\([^()\n]*\))?\.[\"'”’]?$")
_BARE_ENDING = re.compile(r"[가-힣]니다$")
# Only join a wrapped line when its last word explicitly continues the sentence.
_CONTINUATION = re.compile(
    r"(?:[가-힣]+(?:을|를|은|는|이|가|의|에|에서|으로|로|와|과|하고|하며|하여|이며)|"
    r"위해|통해|따라|맞춰|있는|없는|수)$"
)
_LIST_OR_HEADING = re.compile(r"^(?:#{1,6}\s|```|[-*•]\s|\d{1,2}[.)]\s|[가-힣][.)]\s)")


class KoreanBodyFormatError(ValueError):
    def __init__(self, sentence_number: int, ending_kind: str):
        super().__init__("본문의 모든 문장은 완전한 합니다체로 작성해야 합니다.")
        self.section_id = None
        self.sentence_number = sentence_number
        self.ending_kind = ending_kind

    def safe_hint(self):
        return (
            f"{self} section={self.section_id} sentence={self.sentence_number} "
            f"ending={self.ending_kind}"
        )


def normalize_korean_body(body: str) -> str:
    """Change whitespace and terminal punctuation only, never words or factual claims."""
    body = re.sub(r"[\u200b\ufeff\u2060]", "", body)
    body = body.replace("\r\n", "\n").replace("\r", "\n")
    body = body.replace("\u2028", "\n").replace("\u2029", "\n\n")
    body = re.sub(r"(?<=니다)[。．]", ".", body)
    body = re.sub(r"(?<=니다)[ \t]+\.", ".", body)
    lines = []
    for raw_line in body.split("\n"):
        line = raw_line.strip()
        if _BARE_ENDING.search(line):
            line += "."
        if (
            line
            and lines
            and _CONTINUATION.search(lines[-1])
            and not _LIST_OR_HEADING.match(line)
            and not _LIST_OR_HEADING.match(lines[-1])
        ):
            lines[-1] += " " + line
        else:
            lines.append(line)
    return "\n".join(lines).strip()


def _sentences(line: str):
    start = 0
    # Periods in Latin abbreviations, decimal numbers and dates are not sentence ends.
    for match in re.finditer(r"[.!?]+[\"'”’]?", line):
        if match[0] == ".":
            prefix, suffix = line[: match.start()], line[match.end() :]
            abbreviation = re.search(
                r"\b(?:e\.g|i\.e|etc|Inc|Ltd|Co|vs|(?:[A-Za-z]\.)*[A-Za-z])$", prefix
            )
            numeric_separator = re.search(r"\d$", prefix) and re.match(r"\s*\d", suffix)
            date_end = re.search(r"\b\d{4}\.\s*\d{1,2}\.\s*\d{1,2}$", prefix)
            if abbreviation or numeric_separator or date_end:
                continue
        yield line[start : match.end()].strip()
        start = match.end()
    if line[start:].strip():
        yield line[start:].strip()


def verify_korean_body(body: str) -> None:
    count = 0
    for line in body.splitlines():
        if not line.strip():
            continue
        if _LIST_OR_HEADING.match(line):
            raise KoreanBodyFormatError(count + 1, "list_or_heading")
        for sentence in _sentences(line):
            count += 1
            if not _ENDING.search(sentence):
                kind = "non_formal_or_incomplete"
                if _BARE_ENDING.search(sentence):
                    kind = "missing_terminal"
                elif re.search(r"[!?]$|\.{2,}$", sentence):
                    kind = "terminal_punctuation"
                raise KoreanBodyFormatError(count, kind)
    if not count:
        raise KoreanBodyFormatError(1, "empty")
