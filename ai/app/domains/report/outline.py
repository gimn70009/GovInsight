"""Display document outline markers without changing the words they introduce."""

import re

# Callers must interpret checkbox selections before treating square marks as outlines.
_OUTLINE = re.compile(r"(?:^|(?<=\s))[○●▪•∙□■◦▫◇◆▶▷▸▹►▻◀◁]+[ \t]*")
_NOTE = re.compile(r"(?:^|(?<=\s))[※*]+[ \t]*(?=[가-힣A-Za-z])")
_DASH = re.compile(r"(?:^|(?<=\s))[-–—]\s+(?=[가-힣A-Za-z])")
_LETTER_BULLET = re.compile(
    r"(?:^|(?<=\s))ㅇ\s+(?=[가-힣A-Za-z])|(?:^|\n)[ \t]*o\s+(?=[가-힣A-Za-z])"
)


def outline_display_lines(value: str) -> list[str]:
    """Strip known list markers at boundaries, preserving notes and inline symbols."""
    value = _OUTLINE.sub("\n", value)
    value = _NOTE.sub("\n", value)
    value = _DASH.sub("\n", value)
    value = _LETTER_BULLET.sub("\n", value)
    return [" ".join(line.split()) for line in value.splitlines() if line.strip()]
