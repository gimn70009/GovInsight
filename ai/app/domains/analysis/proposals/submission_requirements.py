"""Conservative reconciliation against explicit submission rows and form references.

This is not a general document classifier. Ambiguous or oversized clauses stay
with the model; a filename or a bare template heading never proves an obligation.
"""

import logging
import re
import unicodedata
from dataclasses import dataclass

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


@dataclass(frozen=True)
class _Source:
    name: str | None
    text: str

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

    def as_item(self) -> PreparationChecklistItem:
        inclusion = f"{self.included_in}에 포함하는 자료입니다. " if self.included_in else ""
        return PreparationChecklistItem(
            title=self.title,
            detail=f"{inclusion}원문 제출 안내에서 확인한 내용입니다. {self.source.excerpt}",
            next_action="담당자가 원문의 제출 대상과 조건 및 시점에 맞춰 서류를 준비합니다.",
            requirement_level=self.level,
            stage=self.stage,
            applies_to=self.condition or "원문에 명시된 제출 대상",
            source=self.source,
        )


def _sources(document: AnalysisDocumentRequest) -> list[_Source]:
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
    return [s for s in result if not re.search(r"참고|견본|예시|기본계획|운영지침", s.name or "")]


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
            if index == len(rows) - 1 and stop < natural_stop:
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


def collect_submission_requirements(
    document: AnalysisDocumentRequest,
) -> list[SubmissionRequirement]:
    result: list[SubmissionRequirement] = []
    for source in _sources(document):
        table_rows = _table_requirements(source)
        result.extend(table_rows)
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
    unique: list[SubmissionRequirement] = []
    for requirement in result:
        identity = (
            _key(requirement.title),
            requirement.stage,
            requirement.level,
            _key(requirement.condition),
        )
        roles = _roles(requirement.source.excerpt)
        duplicate = next(
            (
                old
                for old in unique
                if (_key(old.title), old.stage, old.level, _key(old.condition)) == identity
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


def _roles(quote: str) -> set[str]:
    return set(
        re.findall(
            r"주관(?:연구개발)?기관|공동(?:연구개발)?기관|참여기업|영리기관|비영리기관", _key(quote)
        )
    )


def _matches(item: PreparationChecklistItem, requirement: SubmissionRequirement) -> bool:
    source_roles = _roles(item.source.excerpt) if item.source else set()
    requirement_roles = _roles(requirement.source.excerpt)
    if source_roles and requirement_roles and source_roles.isdisjoint(requirement_roles):
        return False
    name = _key(requirement.title)
    title = _key(re.sub(r"\([^)]*\)|\[[^]]*\]", "", item.title))
    if name == title:
        return True
    # Shared-prefix lists such as '표준 ... 운영계획서 및 협약서' may shorten
    # the second name. Require its complete name in the item's actual citation.
    return bool(
        item.source
        and name in _key(item.source.excerpt)
        and re.search(r"및|와|과|~", item.title)
        and any(_key(term) in title for term in re.findall(_DOCUMENT, requirement.title))
    )


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
    for item in items:
        matched = [r for r in requirements if _matches(item, r)]
        title = re.sub(r"\([^)]*\)|\[[^]]*\]", "", item.title)
        document_count = max(1, len(re.findall(_DOCUMENT, title)))
        exact_match = any(_key(r.title) == _key(title) for r in matched)
        matched_names = {_key(requirement.title) for requirement in matched}
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
