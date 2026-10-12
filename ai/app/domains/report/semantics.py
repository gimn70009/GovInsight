"""Conservative procedure checks for report routes and contacts, using source text only."""

import re
import unicodedata
from dataclasses import dataclass

from app.domains.report.schemas.request import ReportDocumentRequest
from app.domains.report.submission_documents import SourcePart, _parts, source_priority

_OBJECTION = re.compile(
    r"중복\s*성?\s*(?:제기|검토)|이의\s*(?:신청|제기)|불복|민원|신고\s*(?:방법|접수)"
)
_POST_SELECTION = re.compile(
    r"(?:선정|선발|채택|협약|협약\s*체결)\s*(?:후|이후)|"
    r"(?:선정된|선정\s*기관|협약\s*대상).{0,20}(?:제출|접수)|정산\s*(?:서류|방법|제출)"
)
_OPINION = re.compile(r"의견\s*(?:제출|접수|서)|의견이\s*있는")
_PUBLIC_COMMENT = re.compile(r"행정\s*예고|입법\s*예고|의견\s*수렴")
_PHONE = re.compile(r"(?<!\d)(?:0\d{1,3}[-)\s]+\d{3,4}[-\s]+\d{4}|1[568]\d{2}-\d{4})(?!\d)")
_EMAIL = re.compile(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", re.I)
_URL = re.compile(
    r"(?:https?://|www\.)[^\s<>),;]+|"
    r"[a-z0-9.-]+\.(?:go\.kr|or\.kr|co\.kr|org|com)(?:/[^\s<>),;]*)?", re.I,
)
_CONTACT = re.compile(r"문의|담당|고객\s*센터|콜\s*센터|연락처|contact|helpdesk|\btel\b", re.I)
_CONTACT_CUE = re.compile(
    r"문의|고객\s*센터|콜\s*센터|연락처|담당\s*(?:자|부서)?\s*[:：]|"
    r"contact|helpdesk|\btel\b", re.I,
)
_DEPARTMENT = re.compile(r"[가-힣A-Za-z0-9]{2,}(?:팀|과|실|부|센터)")
_SOURCE_DATE = re.compile(
    r"(?<!\d)(20\d{2}|[\'‘’]\d{2})\s*[.년/-]\s*"
    r"(\d{1,2})\s*[.월/-]\s*(\d{1,2})(?:\s*일)?"
)
_ADDRESS = re.compile(r"\(?\d{5}\)?|[가-힣]+(?:시|군|구)\s+[가-힣0-9]+(?:로|길)")
_RESPONSIBLE = re.compile(r"담당\s*(?:부서|기관|팀|과)|문의\s*(?:처|부서|담당)")
_SUBMISSION = re.compile(
    r"(?:신청|접수|제출|등록)\s*(?:방법|방식|처|장소|경로)|"
    r"(?:신청|접수|제출)\s*[:：(]|전산\s*등록|온라인.{0,12}(?:신청|접수|제출)|"
    r"(?:신청|접수|제출).{0,12}(?:온라인|우편|방문|이메일|전자우편)|"
    r"(?:계획서|신청서|제안서)\s*(?:접수|제출)"
)
_ROUTE = re.compile(
    r"온라인|시스템|홈페이지|우편|방문|이메일|전자우편|공문|등록처|제출처|접수처|접수\s*방법"
)
_ROW_LABEL = re.compile(
    r"(?:신청|접수|제출|등록)\s*(?:방법|방식|처|장소|경로)|전산\s*등록처|"
    r"(?:신청|접수|제출)\s*[:：(]|(?:계획서|신청서|제안서)\s*접수|온라인.{0,12}(?:신청|접수|제출)"
)
_HEADING = re.compile(r"^(?:\d+(?:[-.]\d+)*[.)]?\s+|[□■]\s*)")
_NOTE = re.compile(r"^[*※]|^(?:단[,.，]|다만|주의|유의)|동시에|불필요|필수|접수.*제외")


@dataclass(frozen=True)
class _Row:
    text: str
    context: str
    note: bool = False


def _key(text: str) -> str:
    return re.sub(r"[\s·ㆍ]", "", unicodedata.normalize("NFKC", text)).casefold()


def _clean(text: str) -> str:
    return re.sub(r"^[□■○●ㅇ•*\s]+", "", " ".join(text.split())).strip()


def _rows(text: str) -> list[_Row]:
    rows = []
    section = ""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    for line in lines:
        # Section context is retained through flattened table cells. A labelled
        # data row must not turn the following application section into an appeal.
        heading = len(line) <= 110 and not re.search(r"[:：]|https?://", line)
        if heading and (
            _HEADING.match(line)
            or _OBJECTION.search(line)
            or _POST_SELECTION.search(line)
            or re.fullmatch(r"[ㅇ○□\s]*(?:접수\s*및\s*)?문의(?:처|사항)?", line)
            or re.fullmatch(
                r"(?:신청|접수|제출)\s*(?:방법|안내|절차|처|장소)|문의(?:처|사항)?", line,
            )
        ):
            section = line
        rows.append(_Row(_clean(line), section + "\n" + _clean(line), bool(_NOTE.search(line))))
    return rows


def _primary_opinion(document: ReportDocumentRequest) -> bool:
    # The title establishes the notice's purpose; a secondary appeal paragraph
    # in a grant application cannot promote itself into the main procedure.
    return bool(_PUBLIC_COMMENT.search(document.title or ""))


def _wrong_procedure(text: str, document: ReportDocumentRequest) -> bool:
    if _POST_SELECTION.search(text) or _OBJECTION.search(text):
        return True
    return bool(_OPINION.search(text) and not _primary_opinion(document))


def _route_valid(text: str, document: ReportDocumentRequest) -> bool:
    if _wrong_procedure(text, document):
        return False
    lines = text.splitlines()
    if (len(lines) > 1 and _CONTACT_CUE.search(lines[0])
            and not re.search(r"접수\s*및\s*문의", lines[0])):
        return False
    row = lines[-1] if lines else text
    if _CONTACT.search(row) and not re.search(
        r"(?:신청|접수|제출|등록)\s*(?:방법|방식|처|장소|경로)\s*[:：]", row,
    ):
        return False
    combined = bool(re.search(r"접수\s*및\s*문의", text))
    submission = bool(_SUBMISSION.search(text)) or combined
    if _primary_opinion(document) and _OPINION.search(text):
        submission = True
    # A contact URL or an institution's address alone is not an application route.
    return submission and bool(
        _ROUTE.search(text) or _URL.search(text) or _EMAIL.search(text)
        or (combined and _ADDRESS.search(text))
    )


def _contact_valid(value: str, evidence: str) -> bool:
    if _PHONE.search(value) or _EMAIL.search(value):
        anchors = _PHONE.findall(value) + _EMAIL.findall(value)
        return all(_key(anchor) in _key(evidence) for anchor in anchors)
    # Publication authority + date has neither a contact channel nor an explicit
    # responsible department. An agency name ending in 부 alone is insufficient.
    return bool(_RESPONSIBLE.search(evidence) and _DEPARTMENT.search(value)
                and len(value) <= 80 and not _ADDRESS.search(value)
                and not re.search(
                    r"공고\s*기관|공고일|제출|검토|확인|추천|통보|심사|하여|경우|않|→", value,
                ))


def _date_keys(text: str) -> set[tuple[int, int, int]]:
    found = set()
    for match in _SOURCE_DATE.finditer(text):
        year = int(match[1].strip("'‘’"))
        found.add((year + 2000 if year < 100 else year, int(match[2]), int(match[3])))
    return found


def _matching_contexts(
    value: str, quote: str, document: ReportDocumentRequest, *, dates: bool = False,
) -> list[str]:
    source_rows = [
        row for part in _parts(document) if source_priority(part) == 0
        for row in _rows(part.text) if len(row.text) <= 1200
    ]
    quote_key = _key(quote)
    value_key = _key(value)
    anchors = _URL.findall(value) + _EMAIL.findall(value) + _PHONE.findall(value)
    selected = []
    for row in source_rows:
        row_key = _key(row.text)
        supported = (len(row_key) >= 8 and row_key in quote_key) or (
            len(quote_key) >= 8 and quote_key in _key(row.context)
        )
        if not supported:
            continue
        direct = len(value_key) >= 4 and value_key in row_key
        anchored = any(_key(anchor) in row_key for anchor in anchors)
        if direct or anchored:
            selected.append(row.context)
    if selected:
        return selected
    # Short, exact quotations can omit the heading; restore its context from the
    # same source occurrence before accepting an address or submission command.
    if len(quote) <= 1200:
        quoted = [row.context for row in source_rows
                  if len(quote_key) >= 8 and (
                      quote_key in _key(row.context)
                      or (len(_key(row.text)) >= 12 and _key(row.text) in quote_key)
                  )]
        if quoted:
            return quoted
    if dates:
        # Rendering can turn '26.10.15 into 2026년 10월 15일. Use date anchors
        # only after exact quotation matching, and return every matching source
        # section so a shared date cannot silently choose the application role.
        anchors = _date_keys(quote) or _date_keys(value)
        return [row.context for row in source_rows if len(row.text) <= 300
                and anchors and anchors & _date_keys(row.text)]
    return []


def field_evidence_valid(
    kind: str, value: str, quote: str, document: ReportDocumentRequest,
) -> bool:
    """Check field meaning after the caller has checked verbatim source support.

    ``quote`` may be a short model quotation or the complete source text used by
    legacy fallback/reuse. No summary or model procedure label is source proof.
    """
    if kind not in {"destination", "contact", "deadline"}:
        return True
    payload = re.split(r"[:：]", value, maxsplit=1)[-1]
    if (not quote.strip() or "원문 확인" in value
            or not re.search(r"[가-힣A-Za-z0-9@]", payload)):
        return False
    if kind == "contact" and not _contact_valid(value, quote):
        return False
    if _wrong_procedure(value, document):
        return False
    if kind in {"deadline", "contact"}:
        contexts = _matching_contexts(value, quote, document, dates=kind == "deadline")
        return (all(not _wrong_procedure(context, document) for context in contexts)
                if contexts else not _wrong_procedure(quote, document))
    contexts = _matching_contexts(value, quote, document)
    if contexts:
        return any(_route_valid(context, document) for context in contexts)
    return len(quote) <= 1200 and _route_valid(quote, document)


_EXPLICIT_ROUTE = re.compile(
    r"(?:신청|접수|제출|등록)\s*(?:방법|방식|처|장소|경로)?\s*[:：]"
)
_OTHER_FIELD = re.compile(
    r"※\s*붙임\s*\d|(?:제출|구비)\s*서류\s*[:：]|"
    r"(?:신청|접수|제출)\s*(?:기한|기간|마감)\s*[:：]|문의(?:처|사항)?\s*[:：]"
)


def _route_excerpt(text: str) -> str | None:
    if re.match(r"^\[|^[①-⑳]", text) or re.search(r"접수\s*절차\s*\]?$", text):
        return None
    marker = _EXPLICIT_ROUTE.search(text)
    if marker:
        prefix = text[:marker.start()]
        # Preserve country/document qualifiers, but separate an earlier field
        # from a route packed onto the same physical line.
        boundary = max(prefix.rfind(":") + 1, prefix.rfind("：") + 1)
        if boundary:
            separator = re.search(r"\s[-–—]\s*([^:：]*)$", prefix)
            start = separator.start(1) if separator else marker.start()
        else:
            start = 0
        value = text[start:]
        following = _OTHER_FIELD.search(value, marker.end() - start)
        if following:
            value = value[:following.start()]
        return value.strip(" -·")
    if (re.search(
            r"온라인.{0,12}(?:일괄\s*)?(?:접수|제출)|전산등록과\s*동시에|"
            r"(?:우편|방문).{0,25}(?:접수|제출)", text,
    )
            and not re.search(r"문의|안내|목적|등록처|제출처|접수처", text)):
        return text.strip(" -·")
    return None


def _recovery_parts(document: ReportDocumentRequest) -> list[SourcePart]:
    families: dict[str, SourcePart] = {}
    for part in _parts(document):
        if (source_priority(part) != 0
                or re.search(r"지침|가이드|매뉴얼", (part.name or "") + (part.archive or ""))):
            continue
        # HWPX and PDF copies of the same notice often contain identical rows,
        # but the PDF wraps notes or collapses tables. Recover from the copy with
        # more preserved line boundaries; do not splice either copy's columns.
        name = re.sub(r"\.(?:hwpx?|pdf)$", "", part.name or "", flags=re.I)
        family = _key((part.archive or "") + "/" + name)
        existing = families.get(family)
        if existing is None or len(part.text.splitlines()) > len(existing.text.splitlines()):
            families[family] = part
    return list(families.values())


def _source_values(document: ReportDocumentRequest, *, contact: bool) -> str | None:
    found = []
    seen = set()
    route_urls = set()
    for part in _recovery_parts(document):
        rows = _rows(part.text)
        consumed = set()
        for index, row in enumerate(rows):
            if index in consumed:
                continue
            if contact:
                value = row.text
                if (re.fullmatch(r"연락처|전화(?:번호)?|이메일|전자우편", value)
                        and index + 1 < len(rows)
                        and (_PHONE.search(rows[index + 1].text)
                             or _EMAIL.search(rows[index + 1].text))):
                    value += " " + rows[index + 1].text
                    consumed.add(index + 1)
                valid = (_CONTACT_CUE.search(row.context)
                         and _contact_valid(value, row.context + "\n" + value)
                         and not _wrong_procedure(row.context, document)
                         and len(_CONTACT.findall(value)) <= 3
                         and not re.search(r"세부사업명|담당부서.*연락처|^연락처\s*[(:（]", value))
            else:
                value = _route_excerpt(row.text)
                if (re.fullmatch(r"(?:신청|접수|제출|전산\s*등록)\s*(?:방법|처|장소)", row.text)
                        and index + 1 < len(rows)
                        and (_EMAIL.search(rows[index + 1].text)
                             or _URL.search(rows[index + 1].text))):
                    value = row.text + ": " + rows[index + 1].text
                if (row.text == "주소" and re.search(r"접수\s*및\s*문의", row.context)
                        and index + 1 < len(rows) and _ADDRESS.search(rows[index + 1].text)):
                    value = "주소: " + rows[index + 1].text
                    consumed.add(index + 1)
                    if index + 2 < len(rows) and _DEPARTMENT.search(rows[index + 2].text):
                        value += "\n" + rows[index + 2].text
                        consumed.add(index + 2)
                valid = bool(value and _route_valid(row.context + "\n" + value, document)
                             and re.search(r"[가-힣A-Za-z0-9@]",
                                           re.split(r"[:：]", value, maxsplit=1)[-1]))
            if not valid or not value or not 8 <= len(value) <= 300:
                continue
            urls = set(_URL.findall(value))
            if (not contact and urls and urls <= route_urls
                    and re.match(r"^(?:제출|접수|등록)\s*처\s*[:：]", value)):
                continue
            # Preserve adjacent restrictions verbatim. Never pair flattened table
            # columns or concatenate a telephone prefix with a later extension.
            if not contact:
                for next_index, following in enumerate(rows[index + 1:], index + 1):
                    if (not following.note or _EXPLICIT_ROUTE.search(following.text)
                            or _wrong_procedure(following.context, document)):
                        break
                    value += "\n" + following.text
                    consumed.add(next_index)
            key = _key(value)
            if key not in seen:
                seen.add(key)
                route_urls.update(urls)
                found.append(value)
    joined = "\n".join(found)
    # Long/ambiguous tables remain for the normal model/check path; do not drop
    # additional routes or restrictions merely to make a shorter fallback.
    return joined if found and len(joined) <= 900 else None


def source_submission_routes(document: ReportDocumentRequest) -> str | None:
    """Recover complete explicit application route rows from primary source files."""
    return _source_values(document, contact=False)


def source_contacts(document: ReportDocumentRequest) -> str | None:
    """Recover explicit contact rows, retaining their original department/channel."""
    return _source_values(document, contact=True)
