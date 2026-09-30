"""Keep unresolved submission checks separate from decisions for a meeting."""
import re

_PREFIX = re.compile(r"제출\s*여부\s*확인\s*:")


def separate_submission_reviews(
    agenda: list[str], notes: list[str],
) -> tuple[list[str], list[str]]:
    decisions: list[str] = []
    reviews: list[str] = []
    for value in agenda:
        if _PREFIX.match(value.strip()):
            reviews.append(value)
        else:
            decisions.append(value)
    # Older results packed several checks into one agenda item. Unpack only
    # explicitly marked review items, preserving all other meeting decisions.
    unpacked = []
    for value in notes + reviews:
        if _PREFIX.match(value.strip()):
            unpacked.extend("제출 여부 확인: " + part.strip()
                            for part in _PREFIX.split(value)[1:] if part.strip())
        elif value.strip():
            unpacked.append(value.strip())
    return list(dict.fromkeys(decisions)), list(dict.fromkeys(unpacked))
