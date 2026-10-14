"""Conservative reconciliation of explicit table cells, without guessing empty values."""

import re
from dataclasses import dataclass

from app.domains.monitoring.parsers.table_text import tables


@dataclass(frozen=True)
class SubmissionTableRow:
    title: str
    quote: str
    context: str
    marker: str
    condition: str


def submission_table_rows(text: str) -> list[SubmissionTableRow]:
    result = []
    for start, end, rows in tables(text):
        context = text[max(0, start - 600) : start]
        if re.search(r"작성\s*예시|작성\s*견본|첨부\s*목록|양식\s*목록|참고\s*자료", context):
            continue
        headers = []
        name_column = -1
        header_index = -1
        for index, row in enumerate(rows[:5]):
            names = [re.sub(r"\s+", "", cell) for cell in row]
            if len(set(filter(None, names))) < 2:
                continue
            candidates = []
            for column, name in enumerate(names):
                if _name_header(name):
                    count = sum(
                        bool(
                            re.search(
                                r"(?:계획서|신청서|확인서|증명서|동의서|증빙자료|발표자료|공문)(?:\s*\([^)]*\))?$",
                                candidate[column],
                            )
                        )
                        for candidate in rows[index + 1 :]
                    )
                    if count:
                        candidates.append((count, column))
            candidates.sort(reverse=True)
            if candidates and (len(candidates) == 1 or candidates[0][0] > candidates[1][0]):
                headers, name_column, header_index = names, candidates[0][1], index
            if headers:
                break
        if not headers:
            continue
        if not headers[name_column].startswith("제출") and not re.search(
            r"(?:신청|제출|구비)\s*(?:서류|자료)",
            context,
        ):
            continue
        context += "\n" + "\n".join("\t".join(row) for row in rows[:header_index])
        # Shared exceptions must not be lost by deterministic row reconciliation.
        # The model still sees the complete table and its footnotes.
        if re.match(r"\s*[*※]", text[end:]):
            continue
        for row in rows[header_index + 1 :]:
            marker = ""
            conditions = []
            row_context = context
            for header, value in zip(headers, row, strict=True):
                if header in ("구분", "필수여부", "제출여부", "제출구분", "필수선택"):
                    marker = value
                elif _name_header(header) and re.fullmatch(
                    r"필수(?:\s*제출)?|해당\s*시(?:\s*제출)?|선택(?:\s*제출)?",
                    value,
                ):
                    marker = value
                elif header in ("제출시기", "제출단계", "시기") and value:
                    row_context = value
                elif header in ("제출대상", "대상", "제출조건", "조건") and value:
                    conditions.append(value)
                elif re.search(
                    r"해당|경우|한함|한하여|한해|면제|생략|미제출|대체|대신|택\s*1", value
                ):
                    conditions.append(value)
                if not marker and re.fullmatch(
                    r"필수(?:\s*제출)?|해당\s*시(?:\s*제출)?|선택(?:\s*제출)?", value
                ):
                    marker = value
            result.append(
                SubmissionTableRow(
                    row[name_column],
                    "\t".join(row),
                    row_context,
                    marker,
                    " / ".join(conditions),
                )
            )
    return result


def _name_header(value: str) -> bool:
    return bool(re.fullmatch(r"서류명|(?:제출|신청|접수|구비)(?:서류|자료)(?:목록|명)?", value))
