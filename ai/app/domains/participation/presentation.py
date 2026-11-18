"""Conservative presentation checks; never repair wording or infer missing facts."""

import re


def complete_sentence(text: str) -> bool:
    value = text.strip()
    if not value or re.search(r"[\r\n\t※⏎■□▪●•◦]|(?:^|\s)[*①-⑳]", value):
        return False
    if re.search(r"(?:^|\s)(?:[-–—]|\d+[.)])\s", value):
        return False
    # Sentence-final punctuation, not decimal points, legal numbers or abbreviations.
    sentences = re.split(r"(?<=[.!?。])\s+(?=[가-힣])", value)
    return all(re.search(r"(?:니다|십시오)[.!。]$", part) for part in sentences)
