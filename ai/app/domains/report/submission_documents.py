"""Project the saved proposal checklist into a report and resolve collected form URLs."""

import re
import unicodedata
from dataclasses import dataclass

from app.domains.report.schemas.request import ReportDocumentRequest


@dataclass(frozen=True)
class SourcePart:
    name: str | None
    text: str
    url: str | None = None
    archive: str | None = None


@dataclass(frozen=True)
class SubmissionDocument:
    title: str
    form: SourcePart | None


_MARKER = re.compile(r"(?m)^\[파일:\s*([^\]\r\n]+)\]\r?\n")
_FORM = re.compile(
    r"양식|서식|신청서|계획서|동의서|확약서|서약서|위임장|조사서|추천서|제안서|설명자료|이력서|확인서|신청자격|협정서|공적조서"
)
_DOCUMENT_LABEL = re.compile(r"(?:제\s*출|구\s*비|신\s*청)\s*서\s*류\s*[:：]")


def source_priority(part: SourcePart) -> int:
    name = part.name or ""
    if re.search(r"참고|가이드|분류|운영지침", (part.archive or "") + " " + name):
        return 2
    if not name or re.search(r"공고|접수.*안내|모집.*안내", name):
        return 0
    return 1 if _FORM.search(name) else 0


def _name_key(value: str) -> str:
    value = re.sub(r"\.(?:hwp[x]?|pdf|docx?|xlsx?|zip)$", "", value, flags=re.I)
    value = re.sub(r"^[\s\d.()\[\]【】]+", "", value)
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", value)).casefold()


def _parts(document: ReportDocumentRequest) -> list[SourcePart]:
    parts = [SourcePart(None, document.content_text or "")]
    for attachment in document.attachments:
        text = attachment.extracted_text or ""
        markers = list(_MARKER.finditer(text))
        if attachment.file_name.lower().endswith(".zip") and markers:
            for i, marker in enumerate(markers):
                end = markers[i + 1].start() if i + 1 < len(markers) else len(text)
                parts.append(
                    SourcePart(
                        marker[1],
                        text[marker.end() : end],
                        attachment.download_url,
                        attachment.file_name,
                    )
                )
        else:
            parts.append(SourcePart(attachment.file_name, text, attachment.download_url))
    return parts


def _form_tokens(value: str, *, requirement: bool = False) -> list[str]:
    value = unicodedata.normalize("NFKC", value)
    if requirement:
        # Remove explanatory notes, but keep identity qualifiers such as 개인/단체.
        value = re.sub(r"\([^()]*?(?:첨부|별도\s*제출|해당\s*시)[^()]*\)", "", value)
    value = re.sub(r"신청\s*서식", "신청서", value)
    value = re.sub(r"신청\s*자격(?:\s*적정성)?(?:\s*확인서)?", "신청자격", value)
    value = re.sub(r"(" + _FORM.pattern + r")", r" \1 ", value)
    return [
        t for t in re.findall(r"[가-힣A-Za-z0-9]+", value) if t not in {"및", "등", "양식", "서식"}
    ]


def _name_matches(tokens: list[str], value: str) -> bool:
    key = _name_key(" ".join(_form_tokens(value)))
    return bool(tokens) and all(_name_key(token) in key for token in tokens)


def _contains_form_heading(tokens: list[str], text: str) -> bool:
    lines = text.splitlines()
    in_checklist = False
    for index, raw in enumerate(lines):
        if not raw.strip() or re.match(r"^\s*\[(?:별지|서식)", raw):
            in_checklist = False
        elif _DOCUMENT_LABEL.search(raw) or re.fullmatch(r"\s*(?:제출|구비|신청)\s*서류\s*", raw):
            in_checklist = True
        if in_checklist:
            continue
        heading = re.sub(r"^\s*\[(?:별지|서식)[^]]*\]\s*", "", raw).strip()
        heading = re.sub(r"^\d+[.)]\s*", "", heading)
        # A checklist mention is not proof that the template is in this file.
        if (
            not 2 <= len(heading) <= 100
            or not _name_matches(tokens, heading)
            or re.search(r"[:：]|제출|구비|목록|\d+\s*부|참고|공고", heading)
        ):
            continue
        if not re.search(r"(?:" + _FORM.pattern + r")(?:\s*\([^()]+\))?$", heading):
            continue
        following = "\n".join(lines[index + 1 : index + 8])
        if re.search(
            r"기관명|신청기관|사업명|대표자|소재지|사업\s*개요|추진\s*계획|성명", following
        ):
            return True
    return False


def _form(title: str, parts: list[SourcePart]) -> SourcePart | None:
    tokens = _form_tokens(title, requirement=True)
    if not any(_FORM.fullmatch(token) for token in tokens):
        return None
    candidates = []
    for part in parts:
        if (
            not part.name
            or not _FORM.search(part.name)
            or re.search(r"예시|견본|샘플|참고용", part.name)
            or (
                re.search(r"공고|운영지침|안내문", part.name)
                and not re.search(r"양식|서식", part.name)
            )
        ):
            continue
        # Require every distinguishing name token; never match on 신청서 alone
        # when the requirement names a particular application procedure.
        if _name_matches(tokens, part.name) or _contains_form_heading(tokens, part.text):
            candidates.append(part)
    unique = {(p.url, p.name, p.archive): p for p in candidates}
    return next(iter(unique.values())) if len(unique) == 1 else None


def submission_documents(document: ReportDocumentRequest) -> list[SubmissionDocument]:
    """Use the same APPLICATION checklist as the business-proposal screen.

    Its analysis has already been saved. Do not re-extract, rewrite, or reject its
    names using a shorter report excerpt; source text is used only to resolve URLs.
    """
    preparation = document.proposal.preparation if document.proposal else None
    if not preparation:
        return []
    parts = _parts(document)
    return [
        SubmissionDocument(item.title, _form(item.title, parts))
        for item in preparation.submission_documents
        if item.stage == "APPLICATION"
    ]


def route_documents(document: ReportDocumentRequest) -> list[SubmissionDocument]:
    """A document explicitly accepted in a submission route is a source-backed minimum."""
    parts = _parts(document)
    found = {}
    for part in parts:
        if source_priority(part) != 0:
            continue
        for line in part.text.splitlines():
            match = re.match(
                r"^\s*([가-힣A-Za-z·ㆍ\s]{1,60}(?:계획서|신청서|제안서))\s*접수\s*\(", line
            )
            if not match or re.search(r"선정|협약|예시|샘플", line):
                continue
            title = " ".join(match[1].split())
            found[title] = SubmissionDocument(title, _form(title, parts))
    return list(found.values())
