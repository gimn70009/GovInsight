"""Read explicit numbered submission tables without inferring from form names."""

import re
from dataclasses import dataclass

_HEADERS = re.compile(
    r"(?P<level>(?:순번|번호|No\.?)\s*제출\s*서류\s*파일\s*(?:형태|형식)"
    r"\s*필수\s*여부\s*대상)|"
    r"(?P<recipient>번호\s*서류\s*유형\s*파일\s*형태\s*제출\s*대상\s*기관\s*비고)",
    re.I,
)
_STOP = re.compile(
    r"\[일부 원문 생략|\[파일:|(?:[IVXⅠ-Ⅹ]+|\d+|[가-하])[.)]?\s*"
    r"(?:문의처|기타\s*유의사항|유의사항|근거\s*법령|평가\s*기준|선정\s*절차)|"
    r"[□■]\s*(?!제출\s*서류)|[*※]\s*제출\s*서류\s*유형별|"
    r"※\s*\d+\s*~\s*\d+\s*서류",
)
_TITLE_END = re.compile(
    r"(?:계획서|신청서|확인서|인정서|동의서|확약서|서약서|증명서|협정서|"
    r"보고서|재무제표|등록증|등본|사본|자료|공문)(?:\s*\([^()]*\))?$"
)


@dataclass(frozen=True)
class SubmissionTableRow:
    title: str
    quote: str
    recipient: str
    required: str | None
    notes: str
    context: str


def numbered_submission_rows(
    text: str,
    *,
    truncated: bool = False,
) -> list[SubmissionTableRow]:
    result = []
    headers = list(_HEADERS.finditer(text))
    for index, header in enumerate(headers):
        context = text[max(0, header.start() - 180) : header.start()]
        # An explicit table heading must precede the column names. Keep its stage.
        labels = list(
            re.finditer(r"(?:[□■○◦]|\d+[.)])\s*[^\n]{0,40}?(?:제출|신청|구비)\s*서류", context)
        )
        if labels:
            label_start = labels[-1].start()
            ancestors = list(
                re.finditer(
                    r"(?:^|\n)\s*(?:[□■]|(?:[IVXⅠ-Ⅹ]+|\d+|[가-하])[.)])\s*"
                    r"[^\n]{2,100}",
                    context[:label_start],
                )
            )
            parent = ancestors[-1][0].strip() if ancestors else ""
            context = parent + "\n" + context[label_start:]
        if not re.search(r"(?:제출|신청|구비)\s*서류", context):
            continue
        end = _STOP.search(text, header.end())
        natural_stop = min(
            end.start() if end else len(text),
            headers[index + 1].start() if index + 1 < len(headers) else len(text),
        )
        stop = min(natural_stop, header.end() + 6000)
        table = text[header.end() : stop]
        starts = []
        cursor = 0
        for number in range(1, 30):
            # The expected number also separates merged text such as '택 14사업자...'.
            row = re.search(
                rf"{number}\s*[.)]?\s*(?:\(서식\s*\d+\)\s*)?(?P<title>[가-힣A-Za-z][^\d]{{1,180}}?)"
                r"\s*(?P<format>hwpx?|pdf|docx?|xlsx?)"
                r"(?=\s|필수|해당|선택|주관|공동|참여|영리|비영리)",
                table[cursor:],
                re.I,
            )
            if row is None:
                break
            title = " ".join(row["title"].split())
            if not _TITLE_END.search(title) or len(title) > 150:
                break
            starts.append((cursor + row.start(), cursor + row.end(), title))
            cursor += row.end()
        for row_index, (start, cells_start, title) in enumerate(starts):
            last = row_index == len(starts) - 1
            if last and (stop < natural_stop or (truncated and natural_stop == len(text))):
                continue
            end = starts[row_index + 1][0] if not last else len(table)
            quote = table[start:end].strip()
            cells = table[cells_start:end].strip()
            # An unparsed following row must never become this row's conditions.
            if re.search(
                r"(?:^|\s)\d{1,2}\s*[^\d]{2,100}(?:PDF|HWP|DOCX?|XLSX?|JPEG|JPG|ZIP)", cells, re.I
            ):
                continue
            required = None
            if header.lastgroup == "level":
                marker = re.match(r"(필수|해당\s*시|선택)\s*(.+)", cells, re.S)
                if marker is None:
                    continue
                required, recipient, notes = marker[1], " ".join(marker[2].split()), ""
            else:
                parts = re.split(r"\s*[-*]\s*", cells, maxsplit=1)
                recipient = " ".join(parts[0].split())
                notes = parts[1] if len(parts) == 2 else ""
            if not recipient or len(recipient) > 200 or re.search(r"\d", recipient):
                continue
            result.append(SubmissionTableRow(title, quote, recipient, required, notes, context))
    return result
