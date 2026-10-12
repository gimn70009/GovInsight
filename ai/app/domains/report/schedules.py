"""Keep announcement dates and other schedule roles outside submission deadlines."""

import re

from app.domains.report.facts import _DATE
from app.domains.report.presentation import deadline_display_lines, submission_display_lines

_ANNOUNCEMENT = re.compile(
    r"^(?:대회\s*)?(?:공고(?:일자|일)?|게시일|등록일)\s*[:：]?\s*(?=['‘’]?\d)"
)
_OTHER = re.compile(r"설명회|행사|평가|선정|발표|협약|사업\s*기간|지원\s*기간")
_SUBMISSION = re.compile(
    r"접수|신청|제출|마감|기한|까지|상시|수시|별도\s*안내|추후\s*(?:안내|공지)"
)


def schedule_fields(value: str) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {"공고일": [], "제출·의견 기한": [], "관련 일정": []}
    lines = submission_display_lines(value, "제출·의견 기한")
    previous_field = None
    for line in deadline_display_lines("\n".join(lines), deduplicate=False):
        if not line or line == "원문 확인 필요":
            continue
        heading = re.split(r"[:：]", line, maxsplit=1)[0]
        named_schedule = (
            bool(re.search(r"[:：]", line))
            and bool(_SUBMISSION.search(heading) or _OTHER.search(heading)
                     or re.search(r"공고|게시일|등록일", heading))
        ) or bool(re.match(r"^(?:상시|수시|별도|추후)\s*", line))
        if previous_field and not _DATE.search(line) and not named_schedule:
            # Sentence formatting must not detach receipt/exception conditions
            # from the date and role they qualify.
            result[previous_field][-1] += " " + line
            continue
        if _ANNOUNCEMENT.search(line):
            field = "공고일"
            line = re.sub(r"^공고(?:일자|일)?\s*[:：]?\s*", "", line)
        elif re.search(r"(?:접수|신청|제출)\s*(?:시작|개시)", heading):
            field = "관련 일정"
        elif _OTHER.search(heading) and not _SUBMISSION.search(heading):
            field = "관련 일정"
        elif _SUBMISSION.search(line):
            field = "제출·의견 기한"
        else:
            # A bare date has no proven role, even when stored in a deadline field.
            field = "관련 일정"
        result[field].append(line)
        previous_field = field
    # Conditions are part of an entry's identity. Deduplicating dates first
    # could attach a repeated domestic date's next sentence to a foreign date.
    return {field: list(dict.fromkeys(entries)) for field, entries in result.items()}
