"""Conservative reconciliation against explicit submission rows and form references.

This is not a general document classifier. Ambiguous or oversized clauses stay
with the model; a filename or a bare template heading never proves an obligation.
"""

import logging
import re
import unicodedata
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from app.domains.analysis.proposals.submission_names import (
    period_submission_evidence,
    same_period_submission_source,
    same_submission_source,
    submission_name,
)
from app.domains.analysis.proposals.submission_tables import numbered_submission_rows
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.schemas.result import (
    EvidenceOrigin,
    PreparationChecklistItem,
    RequirementLevel,
    RequirementSource,
    RequirementStage,
)

logger = logging.getLogger(__name__)

_DOCUMENT = (
    r"계획서|재무제표|등록증|증명서|확인서|동의서|확약서|서약서|신청서|"
    r"제안서|등기부등본|이력서|추천서|소개\s*자료|협약서|보고서|평가표|출석부"
)
_TITLE = re.compile(
    rf"(?P<title>(?:[가-힣A-Za-z][가-힣A-Za-z0-9·ㆍ ()-]{{0,85}}?)?(?:{_DOCUMENT}))"
    r"\s*(?:\([^\n)]{0,35}\))?\s*$"
)
_HEADER = re.compile(r"서류명\s*파일\s*형식\s*비고")
_FORMAT = re.compile(r"(?<![\w.])(?:hwpx?|pdf|docx?|xlsx?)(?:\s*/\s*(?:zip|pdf|hwpx?))*\b", re.I)
_END = re.compile(
    r"(?:[가-하\d]+[.)]\s*)?(?:[○□]\s*)?(?:[(（]제출방법[)）]|"
    r"유의사항|신청\s*방법|제출\s*방법|문의처|평가\s*방법|선정\s*절차)"
)
_MARKER = re.compile(r"(?m)^\[파일:\s*([^\]\r\n]+)\]\s*\n")
_REFERENCE = re.compile(r"\[(별첨|별지|서식)\s*(\d+)[^\]\n]*\]\s*([^\n\[]{2,180})")
_CONDITIONAL_ROW = re.compile(
    rf"(?m)^\s*(?:\d+[.)]\s*)?(?P<title>[가-힣A-Za-z][가-힣A-Za-z0-9 ()·ㆍ-]{{0,70}}"
    rf"(?:{_DOCUMENT}))\s*[▸▶▷]\s*(?P<condition>[^\n]{{2,180}}제출)\s*$"
)
_AFTER_COMPLETION = re.compile(
    r"(?:운영|사업|실습)\s*(?:완료|종료)\s*(?:후|에\s*따라)"
    r"[^.\n]{0,80}(?:\n[^.\n]{0,80})?"
    r"(?P<title>평가표\s*및\s*출석부|결과\s*보고서|실적\s*보고서|정산\s*보고서)"
    r"[을를]\s*제출(?:합니다|한다|하여야\s*한다|해야\s*합니다)"
)


def _key(value: str) -> str:
    value = re.sub(r"\(Co-op\)", "", value, flags=re.I)
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", value)).casefold()


class SubmissionAttachment(Protocol):
    @property
    def file_name(self) -> str: ...

    @property
    def extracted_text(self) -> str | None: ...


class SubmissionSources(Protocol):
    @property
    def content_text(self) -> str | None: ...

    @property
    def attachments(self) -> Sequence[SubmissionAttachment]: ...


@dataclass(frozen=True)
class _Source:
    name: str | None
    text: str
    truncated: bool = False

    def reference(self, quote: str, section: str) -> RequirementSource:
        return RequirementSource(
            origin=EvidenceOrigin.ATTACHMENT if self.name else EvidenceOrigin.NOTICE_BODY,
            attachment_name=self.name,
            section_title=section,
            excerpt=quote.strip(),
        )


@dataclass(frozen=True)
class SubmissionRequirement:
    title: str
    source: RequirementSource
    stage: RequirementStage
    level: RequirementLevel = RequirementLevel.MANDATORY
    condition: str = ""
    included_in: str = ""
    detail: str = ""

    def as_item(self) -> PreparationChecklistItem:
        inclusion = f"{self.included_in}에 포함하는 자료입니다. " if self.included_in else ""
        return PreparationChecklistItem(
            title=self.title,
            detail=(
                f"{inclusion}원문 제출 안내에서 확인한 내용입니다. "
                f"{self.detail or self.source.excerpt}"
            ),
            next_action="담당자가 원문의 제출 대상과 조건 및 시점에 맞춰 서류를 준비합니다.",
            requirement_level=self.level,
            stage=self.stage,
            applies_to=self.condition or "원문에 명시된 제출 대상",
            source=self.source,
        )


def _sources(document: SubmissionSources) -> list[_Source]:
    result = [_Source(None, document.content_text or "")]
    for attachment in document.attachments:
        text = attachment.extracted_text or ""
        markers = list(_MARKER.finditer(text))
        if markers and attachment.file_name.lower().endswith(".zip"):
            for index, marker in enumerate(markers):
                end = markers[index + 1].start() if index + 1 < len(markers) else len(text)
                result.append(_Source(marker[1], text[marker.end() : end]))
        else:
            result.append(_Source(attachment.file_name, text))
    sources = []
    for source in result:
        if re.search(r"참고|견본|예시|기본계획|운영지침", source.name or ""):
            continue
        omitted = re.search(r"\[일부 원문 생략[^\]]*\]", source.text)
        if omitted:
            # Do not join text from opposite sides of an omitted interval.
            source = _Source(source.name, source.text[: omitted.start()], truncated=True)
        sources.append(source)
    return sources


def _level(quote: str) -> tuple[RequirementLevel, str] | None:
    compact = _key(quote)
    if re.search(
        r"제출불필요|제출하지|미제출|제출면제|제출생략|해당없음|선택불가|예시|참고용", compact
    ):
        return None
    if re.search(r"선택", compact):
        return RequirementLevel.OPTIONAL, "선택하여 제출하는 경우"
    if re.search(
        r"해당시|해당자|해당기업|해당기관|최초참여|변경시|한하여|한해|한함|택1|택일|중1|중하나",
        compact,
    ):
        condition = " ".join(quote.split())
        return (RequirementLevel.CONDITIONAL, condition) if len(condition) <= 200 else None
    # Packaging conditions do not make the document optional. Unknown conditions
    # are deliberately not promoted to an unconditional requirement.
    without_packaging = re.sub(
        r"(?:\d+개이상(?:의)?(?:기업|기관)이참여하는경우|"
        r"(?:기업|기관)이\d+개이상인경우|복수(?:의)?(?:기업|기관)이참여하는경우)"
        r"(?:zip|압축)파일로제출",
        "",
        compact,
    )
    if re.search(r"경우|대상만|기관만|기업만|필요시", without_packaging):
        return None
    return RequirementLevel.MANDATORY, ""


def _stage(context: str) -> RequirementStage | None:
    cues = []
    for pattern, stage in (
        (r"(?:신청|접수)\s*서류", RequirementStage.APPLICATION),
        (r"(?:협약|계약)\s*(?:시|단계|체결|서류)", RequirementStage.AGREEMENT),
        (r"선정\s*(?:후|이후)", RequirementStage.POST_SELECTION),
        (r"(?:운영|사업|실습)\s*(?:종료|완료)\s*후", RequirementStage.REPORTING),
    ):
        cues.extend((match.start(), stage) for match in re.finditer(pattern, context))
    if cues:
        return max(cues, key=lambda cue: cue[0])[1]
    if re.search(r"협약|선정|평가|수행|운영", context):
        return None
    return RequirementStage.APPLICATION


def _table_requirements(source: _Source) -> list[SubmissionRequirement]:
    requirements = []
    for header in _HEADER.finditer(source.text):
        context = source.text[max(0, header.start() - 160) : header.start()]
        if not re.search(r"(?:제출|신청|구비)\s*서류", context):
            continue
        if re.search(r"참고|예시|견본", context):
            continue
        stage = _stage(context)
        if stage is None:
            continue
        end = _END.search(source.text, header.end())
        natural_stop = end.start() if end else len(source.text)
        stop = min(natural_stop, header.end() + 4000)
        table = source.text[header.end() : stop]
        rows: list[tuple[int, str]] = []
        previous_end = 0
        for format_match in _FORMAT.finditer(table):
            prefix = table[previous_end : format_match.start()].rstrip()
            previous_end = format_match.end()
            # HWP cells are lines; flattened HTML rows usually follow 제출 or a
            # bullet. Only a short, document-shaped cell before a file type wins.
            prefix = re.split(r"\n|[▶▷‣*]|제출\s*", prefix)[-1].strip()
            prefix = re.sub(r"^\d+[.)]?\s*", "", prefix)
            title_match = _TITLE.fullmatch(prefix)
            if not title_match or re.search(r"참고|작성|불필요|파일명|입력", title_match["title"]):
                continue
            title = title_match["title"].strip()
            start = table.rfind(prefix, 0, format_match.start())
            if start >= 0:
                rows.append((start, title))
        for index, (start, title) in enumerate(rows):
            end = rows[index + 1][0] if index + 1 < len(rows) else len(table)
            if index == len(rows) - 1 and (
                stop < natural_stop or (source.truncated and natural_stop == len(source.text))
            ):
                continue  # The cut might hide an exception to this final row.
            quote = table[start:end].strip()
            if not 5 <= len(quote) <= 300:
                continue
            level = _level(quote)
            if level is not None:
                requirements.append(
                    SubmissionRequirement(
                        title,
                        source.reference(quote, "제출서류 표"),
                        stage,
                        *level,
                    )
                )
    return requirements


def _numbered_table_requirements(source: _Source) -> list[SubmissionRequirement]:
    result = []
    for row in numbered_submission_rows(source.text, truncated=source.truncated):
        stage = _stage(row.context)
        quote = " ".join(row.quote.split())
        if stage is None or not 5 <= len(quote) <= 460:
            continue
        if re.search(r"참고|예시|견본", row.context) or re.search(r"예시|참고용|견본", quote):
            continue
        clauses = re.split(r"\s*[-*]\s*", row.notes)
        exceptions = [
            clause
            for clause in clauses
            if re.search(r"미제출(?!\s*(?:시|한|하면|기관|서류))|면제", clause)
        ]
        alternatives = [clause for clause in clauses if re.search(r"택\s*1|택일", clause)]
        conditional = bool(
            _key(row.required or "") == "해당시"
            or re.search(r"해당\s*시|기업이.*경우\s*해당", row.recipient + row.notes)
            or exceptions
        )
        if re.search(r"제출\s*(?:불필요|생략)|제출하지\s*않", quote):
            continue
        if exceptions and not re.search(r"비영리|영리|기관|기업|상장사", " ".join(exceptions)):
            continue
        level = (
            RequirementLevel.OPTIONAL
            if row.required == "선택"
            else (RequirementLevel.CONDITIONAL if conditional else RequirementLevel.MANDATORY)
        )
        target = row.recipient
        if row.required and _key(row.required) == "해당시":
            target += " (해당 시)"
        limits = exceptions + alternatives
        if limits:
            expanded = target + " / " + " / ".join(limits)
            target = expanded if len(expanded) <= 200 else target + " (상세 설명의 제출 조건 확인)"
        if len(target) > 200:
            continue
        # Keep the complete bounded row in detail; cite a complete clause if long.
        citation = quote
        if len(citation) > 300:
            candidates = limits + clauses
            citation = next((c for c in candidates if 5 <= len(c) <= 300), "")
            if not citation:
                continue
        result.append(
            SubmissionRequirement(
                row.title,
                source.reference(citation, "제출서류 표"),
                stage,
                level,
                target,
                detail=quote,
            )
        )
    return result


def _conditional_submission_sentences(source: _Source) -> list[SubmissionRequirement]:
    # An outsourcing obligation does not prove that this applicant outsources work.
    pattern = re.compile(
        r"(?:모든\s*)?외주\s*용역(?:으로\s*진행하는\s*내용은|은)\s*"
        r"사업\s*계획서\s*제출\s*시\s*"
        r"(?P<title>외주\s*용역\s*활용\s*계획서)\s*제출\s*필수"
    )
    return [
        SubmissionRequirement(
            match["title"],
            source.reference(match[0], "조건부 제출 안내"),
            RequirementStage.APPLICATION,
            RequirementLevel.CONDITIONAL,
            "외주용역을 진행하는 경우",
        )
        for match in pattern.finditer(source.text)
    ]


def _referenced_application_enclosures(sources: list[_Source]) -> list[SubmissionRequirement]:
    """Use a form's enclosure list only when the notice explicitly requests that form."""
    references = set()
    for source in sources:
        if source.name and not re.search(r"공고|모집.*안내|접수.*안내", source.name):
            continue
        for match in re.finditer(
            r"\[(붙임|별첨|별지|서식)\s*(\d+)\]\s*"
            r"(?:신청서식|신청서)[^\[\]]{0,180}?(?<!미)제출"
            r"(?!\s*(?:불필요|면제|생략|하지))",
            source.text,
        ):
            context = source.text[max(0, match.start() - 100) : match.start()]
            if not re.search(r"접수\s*방법", context):
                continue
            instruction = context + match[0]
            if re.search(r"예시|참고|(?:선정|협약)(?:\s*체결)?\s*(?:후|이후|시)", instruction):
                continue
            level = _level(instruction)
            if level is None or level[0] != RequirementLevel.MANDATORY:
                continue
            references.add(match[1] + match[2])
    result = []
    for identifier in sorted(references):
        candidates = [
            source
            for source in sources
            if source.name
            and re.match(r"^\[" + re.escape(identifier) + r"\]", re.sub(r"\s+", "", source.name))
        ]
        if len(candidates) != 1:
            continue
        source = candidates[0]
        for heading in re.finditer(r"신청합니다[.]\s*첨부\s*서류\s*", source.text):
            context = source.text[max(0, heading.start() - 120) : heading.start()]
            if re.search(r"참고|예시|견본|협약|선정\s*후", context):
                continue
            block = source.text[heading.end() : heading.end() + 2000]
            cursor = 0
            for number in range(1, 28):
                row = re.match(rf"\s*{number}\.\s*([^\r\n]{{2,200}})(?=\r?\n|$)", block[cursor:])
                if row is None:
                    break
                cursor += row.end()
                remaining = source.text[heading.end() + cursor :]
                if source.truncated and not remaining.strip():
                    break
                if cursor == len(block) and remaining.strip():
                    break
                next_line = remaining.lstrip().splitlines()[0] if remaining.strip() else ""
                if next_line and not re.match(
                    r"(?:\d+\.|20\s*년|신청업체|대\s*표\s*자)", next_line
                ):
                    break  # A continued clause may contain this row's exemption.
                value = row[1].strip()
                title = re.sub(r"\s*(?:각\s*)?\d+\s*부\s*$", "", value).strip()
                title_match = _TITLE.fullmatch(title)
                level = _level(value)
                if not title_match or level is None or re.search(r"기타|관련|증빙자료", title):
                    continue
                result.append(
                    SubmissionRequirement(
                        title,
                        source.reference(value, "접수 안내에서 지정한 신청서의 첨부서류"),
                        RequirementStage.APPLICATION,
                        *level,
                    )
                )
    return result


def collect_submission_requirements(
    document: SubmissionSources,
    *,
    include_referenced_enclosures: bool = False,
) -> list[SubmissionRequirement]:
    result: list[SubmissionRequirement] = []
    sources = _sources(document)
    for source in sources:
        table_rows = _table_requirements(source) + _numbered_table_requirements(source)
        result.extend(table_rows)
        result.extend(_conditional_submission_sentences(source))
        forms: list[SubmissionRequirement] = []
        definitions = list(_REFERENCE.finditer(source.text))
        for definition in definitions:
            title = re.split(r"\s+[–—-]\s*", definition[3])[0].strip()
            if not _TITLE.fullmatch(title):
                continue
            identifier = definition[1] + definition[2]
            same_id = [m for m in definitions if m[1] + m[2] == identifier]
            if len(same_id) != 1:
                continue
            parents = [
                row
                for row in table_rows
                if re.search(
                    re.escape(identifier) + r".{0,20}(?:포함|제출)",
                    _key(row.source.excerpt),
                )
            ]
            explicit = re.search(r"협약\s*시\s*제출", definition[3])
            if not parents and not explicit:
                continue
            stages = {row.stage for row in parents}
            if len(stages) > 1 or (explicit and stages - {RequirementStage.AGREEMENT}):
                continue
            stage = RequirementStage.AGREEMENT if explicit else parents[0].stage
            form = SubmissionRequirement(
                title,
                source.reference(definition[0], f"제출서류의 {identifier}"),
                stage,
                parents[0].level if parents else RequirementLevel.MANDATORY,
                parents[0].condition if parents else "",
                included_in=parents[0].title if parents else "",
            )
            forms.append(form)
            result.append(form)
        for match in _CONDITIONAL_ROW.finditer(source.text):
            level = _level(match["condition"])
            if level is None or level[0] != RequirementLevel.CONDITIONAL:
                continue
            prefix = _key(source.text[: match.start()])
            preceding = [(prefix.rfind(_key(form.title)), form) for form in forms]
            preceding = [(position, form) for position, form in preceding if position >= 0]
            if not preceding:
                continue
            parent = max(preceding, key=lambda pair: pair[0])[1]
            result.append(
                SubmissionRequirement(
                    match["title"].strip(),
                    source.reference(match[0], "서식 내 조건부 제출자료"),
                    parent.stage,
                    level[0],
                    match["condition"].strip(),
                )
            )
        for match in _AFTER_COMPLETION.finditer(source.text):
            level = _level(match[0])
            if level is not None:
                result.append(
                    SubmissionRequirement(
                        match["title"],
                        source.reference(match[0], "운영 완료 후 제출자료"),
                        RequirementStage.REPORTING,
                        *level,
                    )
                )
    if include_referenced_enclosures:
        result.extend(_referenced_application_enclosures(sources))
    unique: list[SubmissionRequirement] = []
    for requirement in result:
        identity = (
            _document_key(requirement.title),
            requirement.stage,
            requirement.level,
            _key(requirement.condition),
        )
        roles = _roles(requirement.source.excerpt)
        period_evidence = period_submission_evidence(
            requirement.source, submission_name(requirement.title).key,
        )
        duplicate = next(
            (
                old
                for old in unique
                if (_document_key(old.title), old.stage, old.level, _key(old.condition)) == identity
                and period_submission_evidence(old.source, submission_name(old.title).key)
                == period_evidence
                and (
                    not roles
                    or not _roles(old.source.excerpt)
                    or roles == _roles(old.source.excerpt)
                )
            ),
            None,
        )
        if duplicate is None:
            unique.append(requirement)
    return unique


def _document_key(title: str) -> str:
    # Keep identity qualifiers; remove template annotations and action labels.
    title = re.sub(r"\([^)]*(?:해당\s*시|별첨|양자\s*및\s*다자|또는\s*면제)[^)]*\)", "", title)
    title = re.sub(r"\s*제출\s*(?:의무\s*확인|요건|여부|준비)?\s*$", "", title)
    title = re.sub(r"^(?:주관\s*및\s*공동\s*연구개발기관의?)\s*", "", title)
    title = re.sub(r"신청자격(?:\s*적정성)?(?:\s*확인서)?$", "신청자격확인서", title)
    return _key(title)


def _roles(quote: str) -> set[str]:
    return set(
        re.findall(
            r"주관기관|공동기관|참여기관|참여기업|"
            r"영리기관|비영리기관|지자체",
            re.sub(
                r"(주관|공동)(?=(?:및)?(?:공동|참여))",
                r"\1기관",
                _key(quote).replace("연구개발기관", "기관"),
            ),
        )
    )


def _matches(
    item: PreparationChecklistItem, requirement: SubmissionRequirement, *, allow_alias: bool,
) -> bool:
    source_roles = _roles(item.source.excerpt) if item.source else set()
    requirement_roles = _roles(requirement.source.excerpt)
    if source_roles and requirement_roles and source_roles.isdisjoint(requirement_roles):
        return False
    name = _document_key(requirement.title)
    title = _document_key(item.title)
    item_name = submission_name(item.title, split_period=True)
    source_name = submission_name(requirement.title, split_period=True)
    if item_name.references != source_name.references:
        if not allow_alias or (item_name.references and source_name.references):
            return False
    if item_name.submitters != source_name.submitters:
        if (not allow_alias or (item_name.submitters and source_name.submitters)
                or not same_submission_source(item.source, requirement.source)):
            return False
    if item_name.period is not None or source_name.period is not None:
        periods = {n.period for n in (item_name, source_name) if n.period is not None}
        return bool(
            allow_alias and len(periods) == 1 and item_name.key == source_name.key
            and item.stage == requirement.stage
            and item.requirement_level == requirement.level
            and same_period_submission_source(
                item.source, requirement.source, item_name.key, next(iter(periods)),
            )
        )
    if name == title or (allow_alias and item_name.key == source_name.key):
        return True
    # Shared-prefix lists such as '표준 ... 운영계획서 및 협약서' may shorten
    # the second name. Require its complete name in the item's actual citation.
    return bool(
        item.source
        and name in _key(item.source.excerpt)
        and re.search(r"및|와|과|~", item.title)
        and any(_key(term) in title for term in re.findall(_DOCUMENT, requirement.title))
    )


def _preserve_period_submitter(
    target: PreparationChecklistItem, item: PreparationChecklistItem,
) -> PreparationChecklistItem | None:
    """Carry only a quoted submitter into the source row, with its original evidence."""
    if not item.source or not _roles(item.applies_to):
        return target
    subject = _key(item.applies_to)
    if subject not in _key(item.source.excerpt):
        return None
    if target.applies_to != "원문에 명시된 제출 대상":
        return target if subject in _key(target.applies_to) else None
    detail = target.detail
    if _key(item.source.excerpt) not in _key(detail):
        label = ("공고 본문" if item.source.origin == EvidenceOrigin.NOTICE_BODY
                 else item.source.attachment_name)
        detail += f"\n추가 제출 대상 근거({label}): {item.source.excerpt}"
    if len(detail) > 500:
        return None
    if target.source != item.source:
        label = target.source.attachment_name or "공고 본문"
        detail += f"\n제출표 출처: {label}"
    if len(detail) > 500:
        return None
    # The retained citation must support the newly retained submitter too.
    return target.model_copy(update={
        "applies_to": item.applies_to, "detail": detail, "source": item.source,
    })


def reconcile_submission_documents(
    items: list[PreparationChecklistItem],
    document: AnalysisDocumentRequest,
) -> list[PreparationChecklistItem]:
    requirements = collect_submission_requirements(document)
    if not requirements:
        return items
    # Replace only fully matched model items. If the existing API's 27-item cap
    # is exceeded, fail explicitly instead of silently dropping obligations.
    replacements = [requirement.as_item() for requirement in requirements]
    unmatched = []
    references: dict[str, set[tuple[str, ...]]] = {}
    submitters: dict[str, set[tuple[str, ...]]] = {}
    periods: dict[str, set[int]] = {}
    for title in [item.title for item in items] + [row.title for row in requirements]:
        name = submission_name(title, split_period=True)
        if name.references:
            references.setdefault(name.key, set()).add(name.references)
        if name.submitters:
            submitters.setdefault(name.key, set()).add(name.submitters)
        if name.period is not None:
            periods.setdefault(name.key, set()).add(name.period)
    for item in items:
        name = submission_name(item.title, split_period=True)
        allow_alias = (len(references.get(name.key, set())) <= 1
                       and len(submitters.get(name.key, set())) <= 1
                       and len(periods.get(name.key, set())) <= 1)
        matched = [r for r in requirements if _matches(item, r, allow_alias=allow_alias)]
        matched_period = name.period is not None
        if len(matched) == 1 and item.source:
            row = matched[0]
            matched_period |= (
                item.stage == row.stage and item.requirement_level == row.level
                and any(same_period_submission_source(item.source, row.source, name.key, years)
                        for years, _ in period_submission_evidence(item.source, name.key))
            )
        if matched_period:
            # Never choose one of several source obligations for a generic alias.
            if len(matched) != 1:
                unmatched.append(item)
                continue
            index = requirements.index(matched[0])
            merged = _preserve_period_submitter(replacements[index], item)
            if merged is None:
                unmatched.append(item)
                continue
            replacements[index] = merged
        title = re.sub(r"\([^)]*\)|\[[^]]*\]", "", item.title)
        document_count = max(1, len(re.findall(_DOCUMENT, title)))
        exact_match = any(_document_key(r.title) == _document_key(item.title) for r in matched)
        matched_names = {_document_key(requirement.title) for requirement in matched}
        if not exact_match and len(matched_names) < document_count:
            unmatched.append(item)

    combined = replacements + unmatched
    if len(combined) > 27:
        raise ValueError("제출서류가 표시 가능한 범위를 넘어 전체 목록 확인이 필요합니다.")
    logger.info(
        "제출서류 원문 대조 완료. detection_id=%s source_rows=%s original_items=%s final_items=%s",
        document.detection_id,
        len(requirements),
        len(items),
        len(combined),
    )
    return combined
