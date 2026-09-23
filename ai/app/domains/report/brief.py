"""Proposal-independent, grounded submission extraction; URLs never come from the model."""

import json
import re
import unicodedata
from dataclasses import dataclass

from pydantic import BaseModel, ConfigDict, Field

from app.domains.analysis.evidence.selection import allocate_budgets, select_evidence
from app.domains.report.facts import (
    SubmissionFacts,
    _placeholder,
    _usable_deadline,
    submission_facts,
    submission_routes,
)
from app.domains.report.presentation import korean_display, readable_source
from app.domains.report.schemas.request import ReportDocumentRequest
from app.domains.report.submission_documents import (
    SourcePart,
    SubmissionDocument,
    _form,
    _parts,
    route_documents,
    source_priority,
    submission_documents,
)


class Evidence(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(max_length=30)
    quote: str = Field(min_length=3, max_length=700)


class SourcedText(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(
        min_length=2,
        max_length=150,
        description="Verbatim source excerpt. Do not add labels or paraphrase.",
    )
    evidence: Evidence
    display_text: str | None = Field(
        default=None,
        max_length=150,
        description="Short Korean value preserving source numbers, conditions and contacts.",
    )
    fragments: list[str] = Field(default_factory=list, max_length=4)


class RequiredDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=2, max_length=100)
    condition: str | None = Field(max_length=100)
    evidence: Evidence
    form_source_id: str | None = Field(max_length=30)


class BriefOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    applicants: list[SourcedText] = Field(max_length=3)
    deadlines: list[SourcedText] = Field(max_length=4)
    destinations: list[SourcedText] = Field(max_length=4)
    contacts: list[SourcedText] = Field(max_length=3)
    documents: list[RequiredDocument] = Field(max_length=15)


class DisplayEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid")
    evidence: Evidence
    fragments: list[str] = Field(
        max_length=4, description="Verbatim excerpts in source order, or [] for the entire quote."
    )
    display_text: str = Field(
        min_length=2,
        max_length=150,
        description="One short Korean item. Keep source numbers, conditions, URLs and contacts.",
    )


class BriefModelOutput(BaseModel):
    """Model emits each source excerpt once; legacy extraction values are derived locally."""

    model_config = ConfigDict(extra="forbid")
    applicants: list[DisplayEvidence] = Field(max_length=3)
    deadlines: list[DisplayEvidence] = Field(max_length=4)
    destinations: list[DisplayEvidence] = Field(max_length=4)
    contacts: list[DisplayEvidence] = Field(max_length=3)
    documents: list[RequiredDocument] = Field(max_length=15)

    def extracted(self) -> BriefOutput:
        fields = {}
        for name in ("applicants", "deadlines", "destinations", "contacts"):
            fields[name] = []
            for item in getattr(self, name):
                raw = " ".join(item.fragments) if item.fragments else item.evidence.quote
                raw = " ".join(raw.split())
                if not 2 <= len(raw) <= 150:
                    continue
                fields[name].append(SourcedText(text=raw, **item.model_dump()))
        return BriefOutput(**fields, documents=self.documents)


@dataclass(frozen=True)
class BriefContext:
    payload: str
    sources: dict[str, SourcePart]


@dataclass(frozen=True)
class SubmissionBrief:
    facts: SubmissionFacts
    documents: list[SubmissionDocument]
    note: str | None = None


def build_context(document: ReportDocumentRequest, budget: int) -> BriefContext:
    # Keep file boundaries and distribute input capacity instead of sending entire attachments.
    all_parts = _parts(document)
    # Short notices carry dates/contacts; long blank forms must not crowd them out.
    priority = source_priority

    selected = sorted(range(len(all_parts)), key=lambda i: (priority(all_parts[i]), i))[:40]
    parts = [all_parts[i] for i in sorted(selected)]
    primary = [i for i, part in enumerate(parts) if priority(part) == 0]
    other = [i for i in range(len(parts)) if i not in primary]
    budgets = [0] * len(parts)
    for i, amount in zip(
        primary,
        allocate_budgets([len(parts[i].text) for i in primary], budget * 3 // 4),
        strict=True,
    ):
        budgets[i] = amount
    for i, amount in zip(
        other,
        allocate_budgets(
            [min(len(parts[i].text), 600 if priority(parts[i]) == 1 else 150) for i in other],
            budget - sum(budgets),
        ),
        strict=True,
    ):
        budgets[i] = amount
    # Return unused form capacity to notices.
    extra = allocate_budgets(
        [max(0, len(parts[i].text) - budgets[i]) for i in primary], budget - sum(budgets)
    )
    for i, amount in zip(primary, extra, strict=True):
        budgets[i] += amount
    sources = {}
    entries = []
    for index, (part, limit) in enumerate(zip(parts, budgets, strict=True)):
        selected = select_evidence(part.text, limit)
        source_id = f"source-{index}"
        sources[source_id] = SourcePart(part.name, selected.text, part.url, part.archive)
        entries.append(
            {
                "source_id": source_id,
                "file_name": part.name,
                "archive": part.archive,
                "text": selected.text,
                "coverage": selected.metadata(),
            }
        )
    checklist = [item.title for item in submission_documents(document)]
    payload = json.dumps(
        {"title": document.title, "sources": entries, "saved_submission_checklist": checklist},
        ensure_ascii=False,
    )
    return BriefContext(payload, sources)


def _compact(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).translate(str.maketrans("‘’“”", "''\"\""))
    return re.sub(r"\s+", "", value)


def _supported(evidence: Evidence, context: BriefContext) -> bool:
    source = context.sources.get(evidence.source_id)
    return bool(
        source
        and bool(_compact(evidence.quote))
        and _compact(evidence.quote) in _compact(source.text)
        and "[일부 원문 생략" not in evidence.quote
    )


def _ordered_fragments(fragments: list[str], quote: str) -> bool:
    cursor = 0
    quote = _compact(quote)
    for fragment in fragments:
        fragment = _compact(fragment)
        offset = quote.find(fragment, cursor) if len(fragment) >= 2 else -1
        if offset < 0:
            return False
        cursor = offset + len(fragment)
    return bool(fragments)


def _values(items: list[SourcedText], context: BriefContext, kind: str) -> str:
    values = []
    for item in items:
        source = context.sources.get(item.evidence.source_id)
        if not source or source_priority(source) != 0:
            continue
        value = " ".join(item.text.split())
        cited = _supported(item.evidence, context)
        if item.fragments:
            if not cited or not _ordered_fragments(item.fragments, item.evidence.quote):
                continue
            value = " ".join(" ".join(f.split()) for f in item.fragments)
        elif _compact(value) in _compact(source.text):
            # A verbatim value is independently verifiable even if the model's
            # surrounding quotation contains an added heading or ellipsis.
            pass
        elif not (cited and _ordered_fragments(value.split(), item.evidence.quote)):
            continue
        if _placeholder(value):
            continue
        if kind == "applicant":
            without_headers = re.sub(r"(?:국내)?(?:주관|공동)기관(?:자격)?", "", value)
            if _placeholder(without_headers):
                continue
        if kind == "contact" and not re.search(r"@|\d{2,}", value):
            quote = " ".join(item.evidence.quote.split())
            if cited and len(quote) <= 150 and re.search(r"@|\d{2,}", quote):
                value = quote
        display = korean_display(value, item.evidence.quote, item.display_text) if cited else None
        if kind == "deadline":
            if not _usable_deadline(display or value) or re.search(
                r"제출\s*방법|제출\s*서류|문의처|붙임|동의서|이내/년|억원|선정평가|지원기간",
                value,
            ):
                continue
        # If an attempted Korean rewrite fails checks, do not expose the broken/raw alternative.
        if item.display_text is not None and display is None:
            continue
        value = display or readable_source(value, kind)
        if value:
            # Split labelled roles only, never slashes inside a URL or unlabelled conditions.
            value = re.sub(r"\s+(?:—|/)\s+(?=[^:/\n]{1,30}: )", "\n", value)
        if value and value not in values:
            values.append(value)
    joined = "\n".join(values)
    return joined if 0 < len(joined) <= 300 else "원문 확인 필요"


def validate_brief(
    output: BriefOutput, context: BriefContext, document: ReportDocumentRequest
) -> SubmissionBrief:
    destination = _values(output.destinations, context, "destination")
    source_routes = submission_routes(document)
    if not any(item.display_text is not None for item in output.destinations):
        destination = source_routes or destination
    elif destination != "원문 확인 필요" and source_routes:
        # A fluent rewrite must not silently drop the explicit bilateral exclusion rule.
        for clause in source_routes.split(" / "):
            if re.search(r"한\s*쪽.*제외", clause) and readable_source(clause, "destination"):
                if not re.search(r"(?:한\s*쪽|일방).*제외", destination):
                    destination += "\n" + clause
    facts = SubmissionFacts(
        _values(output.applicants, context, "applicant"),
        _values(output.deadlines, context, "deadline"),
        destination,
        _values(output.contacts, context, "contact"),
    )
    # Missing model fields may use labelled source excerpts, never prior model claims.
    fallback = submission_facts(document, source_only=True)
    groups = {
        "applicant": output.applicants,
        "deadline": output.deadlines,
        "destination": output.destinations,
        "contact": output.contacts,
    }
    facts = SubmissionFacts(
        **{
            key: getattr(facts, key)
            if getattr(facts, key) != "원문 확인 필요"
            or any(item.display_text is not None for item in groups[key])
            else readable_source(getattr(fallback, key), key) or "원문 확인 필요"
            for key in groups
        }
    )
    documents = []
    seen = set()
    for item in output.documents:
        source = context.sources.get(item.evidence.source_id)
        if not source or source_priority(source) == 2:
            continue
        if not _supported(item.evidence, context):
            continue
        if _compact(item.title) not in _compact(item.evidence.quote):
            continue
        if _placeholder(item.title):
            continue
        if not re.search(r"제\s*출|구\s*비|필수|신청\s*서류|계획서\s*접수", item.evidence.quote):
            continue
        if re.search(
            r"제출\s*(?:불필요|면제|생략)|제출하지\s*않|참고용|선정\s*후|협약\s*후",
            item.evidence.quote,
        ):
            continue
        title = " ".join(item.title.split())
        condition = item.condition
        if not condition:
            # Only recover a condition immediately attached to this document's name.
            adjacent = re.search(
                re.escape(item.title) + r"\s*\(\s*(해당\s*시)\s*\)", item.evidence.quote
            )
            if adjacent:
                condition = adjacent[1]
        if condition:
            if _compact(condition) not in _compact(item.evidence.quote):
                continue
            title += " (" + " ".join(condition.split()) + ")"
        if title in seen:
            continue
        seen.add(title)
        form = None
        if item.form_source_id:
            source = context.sources.get(item.form_source_id)
            if source and source.name:
                # A valid id is not sufficient: the selected attachment must match this form.
                form = _form(item.title, [source])
        if not item.form_source_id:
            form = _form(item.title, list(context.sources.values()))
        documents.append(SubmissionDocument(title, form))
    note = None
    if not documents:
        documents = route_documents(document)
        if documents:
            note = "전체 제출서류 목록은 원문 확인 필요"
    if not documents and submission_documents(document):
        note = "기존 체크리스트의 제출 근거 재확인 필요"
    return SubmissionBrief(facts, documents, note)


def fallback_brief(document: ReportDocumentRequest, note: str) -> SubmissionBrief:
    facts = submission_facts(document)
    facts = SubmissionFacts(
        **{
            key: readable_source(getattr(facts, key), key) or "원문 확인 필요"
            for key in ("applicant", "deadline", "destination", "contact")
        }
    )
    return SubmissionBrief(facts, submission_documents(document), note)
