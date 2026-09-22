"""Build actionable submission documents from saved, attributable evidence. No model calls."""

import re
import unicodedata
from dataclasses import dataclass

from app.domains.report.facts import _ANY_LABEL, _find, _source_lines
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
    requirement: str
    submitter: str
    form: SourcePart | None


_MARKER = re.compile(r"(?m)^\[파일:\s*([^\]\r\n]+)\]\r?\n")
_DOC = re.compile(
    r"신청서|계획서|동의서|확약서|확인서|증명서|등록증|정관|공문|명부|명단|재무제표|이력서|견적서|조사서|추천서|제안서|설명자료|실적서|서약서|위임장|보고서|사본"
)
_FORM = re.compile(
    r"양식|서식|신청서|계획서|동의서|확약서|서약서|위임장|조사서|추천서|제안서|설명자료|이력서"
)
_ROLES = re.compile(
    r"(?:주관|공동|참여|수행|신청|지원|수요|공급)(?:연구개발)?(?:기관|기업)|지방자치단체|지자체|연구책임자|대표자"
)
_CONDITIONAL = re.compile(r"해당\s*(?:시|자|기업|기관|하는)|경우|조건부|필요\s*시|택\s*1|또는")
_NOT_APPLICATION = re.compile(r"선정\s*후|협약\s*(?:시|단계|체결\s*후)|사후|정산\s*시")
_NOT_REQUIRED = re.compile(
    r"참고용|작성\s*예시|제출\s*(?:불필요|면제|생략)|제출하지\s*않|선택\s*제출"
    r"|[（(]\s*(?:선택|권장)\s*[)）]|선택사항|권장서류"
)
_DOCUMENT_LABEL = re.compile(r"(?:제\s*출|구\s*비|신\s*청)\s*서\s*류\s*[:：]")


def _normalized(value: str) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value)).casefold()


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


def _form(title: str, parts: list[SourcePart]) -> SourcePart | None:
    key = _name_key(title)
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
        name = _name_key(part.name)
        # The requirement source may be a notice; it is not automatically the form.
        if key and (key in name or (len(name) >= 3 and name in key)):
            candidates.append(part)
        elif (
            _FORM.search(part.name)
            and key in _name_key(part.text)
            and re.search(r"양식|서식", part.name)
        ):
            candidates.append(part)
    unique = {(p.url, p.name, p.archive): p for p in candidates}
    return next(iter(unique.values())) if len(unique) == 1 else None


def _submitter(text: str) -> str:
    roles = list(dict.fromkeys(_ROLES.findall(text)))
    return roles[0] if len(roles) == 1 else "제출 주체 확인 필요"


def _split_documents(value: str) -> list[str]:
    # Do not split alternatives or parenthetical eligibility conditions into requirements.
    if re.search(r"택\s*1|또는", value):
        return [value]
    # Inline PDF tables often flatten several numbered rows into one line.
    value = re.sub(r"(?<![\d-])\b\d{1,2}\)\s*|[①-⑳]\s*", "/", value)
    result = []
    start = depth = 0
    for index, char in enumerate(value):
        if char in "(（[［":
            depth += 1
        elif char in ")）]］":
            depth = max(0, depth - 1)
        elif depth == 0 and char in ",，;/":
            result.append(value[start:index])
            start = index + 1
    result.append(value[start:])
    return [re.sub(r"^\s*(?:\d+[.)]|[①-⑳])\s*", "", v).strip() for v in result if v.strip()]


def _document_title(value: str) -> str | None:
    """Accept document noun phrases, never a whole instruction/table row."""
    value = re.sub(r"^\s*(?:[▷▶•]|\d+[.)])\s*", "", value).strip()
    # Copies and form-index columns are not part of a document's name.
    value = re.sub(r"\s+\d+\s*부(?:\s+.*)?$", "", value).strip()
    value = re.sub(r"\s+(?:서식|별지)\s*\d+(?:-\d+)?$", "", value).strip()
    if (
        not 2 <= len(value) <= 80
        or not _DOC.search(value)
        or _NOT_REQUIRED.search(value)
        or re.search(
            r"[:：<>|]|제\s*출\s*서\s*류|증빙\s*서식|참고해|제출\s*요청"
            r"|(?:합니다|됩니다|하세요|하여야|해야|바랍니다)|(?:를|을)\s*(?:제출|작성|참고)",
            value,
        )
    ):
        return None
    # Reject dangling table columns/punctuation while retaining source conditions.
    if not re.search(
        r"(?:" + _DOC.pattern + r")(?:\s*[（(][^()（）]+[)）])?$", value
    ) and not re.search(r"(?:택\s*1|중\s*하나)$", value):
        return None
    return value


def submission_documents(document: ReportDocumentRequest) -> list[SubmissionDocument]:
    parts = _parts(document)
    result: list[SubmissionDocument] = []
    seen: set[tuple[str, str, str]] = set()

    def add(title: str, requirement: str, submitter: str) -> None:
        title = _document_title(title)
        if not title:
            return
        key = (_name_key(title), requirement, submitter)
        if key in seen:
            return
        seen.add(key)
        result.append(SubmissionDocument(title, requirement, submitter, _form(title, parts)))

    preparation = document.proposal.preparation if document.proposal else None
    if preparation:
        for item in preparation.submission_documents:
            source = item.source
            if (
                item.stage != "APPLICATION"
                or item.requirement_level not in {"MANDATORY", "CONDITIONAL"}
                or not source
            ):
                continue
            candidates = [
                p
                for p in parts
                if (
                    source.origin == "NOTICE_BODY"
                    and p.name is None
                    or source.origin == "ATTACHMENT"
                    and source.attachment_name
                    and source.attachment_name in {p.name, p.archive}
                )
            ]
            excerpt = source.excerpt
            # Archive inventory alone proves file existence, not a submission obligation.
            if (
                _MARKER.search(excerpt + "\n")
                or _NOT_REQUIRED.search(excerpt)
                or _NOT_APPLICATION.search(excerpt)
                or not any(_normalized(excerpt) in _normalized(p.text) for p in candidates)
                or _name_key(item.title) not in _name_key(excerpt)
            ):
                continue
            requirement_context = any(
                _normalized(excerpt) in _normalized(line)
                and _DOCUMENT_LABEL.search(line)
                and not _NOT_REQUIRED.search(line)
                and not _NOT_APPLICATION.search(line)
                for part in candidates
                for line in _source_lines([part.text])
            )
            if not requirement_context and not re.search(r"제출|구비|필수", excerpt):
                continue
            submitter = (
                item.applies_to
                if _normalized(item.applies_to) in _normalized(excerpt)
                else _submitter(excerpt)
            )
            conditional = item.requirement_level == "CONDITIONAL" or bool(
                _CONDITIONAL.search(excerpt)
            )
            # Keep the condition, not just the model's often generic participant label.
            if conditional:
                submitter = (
                    item.applies_to
                    if _normalized(item.applies_to) in _normalized(excerpt)
                    else "적용 조건 확인 필요"
                )
            add(item.title, "조건부" if conditional else "필수", submitter)

    # Low-fit/general notices may have no proposal preparation; explicit source lists still apply.
    for part in parts:
        lines = _source_lines([part.text])
        source_applicant = _find(
            lines, r"(?:신청|지원|제출|참여)\s*(?:대상|주체|자격|기관)\s*[:：]", applicant=True
        )
        for line in lines:
            label = _DOCUMENT_LABEL.search(line)
            if not label or _NOT_APPLICATION.search(line):
                continue
            prefix, value = line[: label.start()], line[label.end() :].strip()
            if not value or _NOT_REQUIRED.search(prefix):
                continue
            following_label = _ANY_LABEL.search(value)
            if following_label:
                value = value[: following_label.start()].strip()
            group_conditional = bool(_CONDITIONAL.search(prefix))
            group_submitter = _submitter(prefix)
            if (
                group_submitter == "제출 주체 확인 필요"
                and source_applicant
                and len(source_applicant) <= 120
            ):
                group_submitter = source_applicant
            for title in _split_documents(value):
                if not title or _NOT_APPLICATION.search(title):
                    continue
                role = _submitter(title)
                if role == "제출 주체 확인 필요":
                    role = group_submitter
                conditional = group_conditional or bool(_CONDITIONAL.search(title))
                if conditional and _CONDITIONAL.search(prefix):
                    role = prefix.strip() or "적용 조건 확인 필요"
                add(title, "조건부" if conditional else "필수", role)
    return result
