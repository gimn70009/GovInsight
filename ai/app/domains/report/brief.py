"""Reuse verified proposal documents or extract them from sources; keep URLs server-owned."""

import json
import re
import unicodedata
from dataclasses import dataclass, field

from pydantic import BaseModel, ConfigDict, Field

from app.domains.analysis.evidence.selection import allocate_budgets
from app.domains.analysis.proposals.submission_evidence import (
    submission_section_contains as _submission_section_contains,
)
from app.domains.analysis.schemas.result import RequirementSource
from app.domains.report.applicants import checkbox_applicants
from app.domains.report.evidence import select_report_evidence
from app.domains.report.fact_reuse import FACT_FIELDS, verified_submission_facts
from app.domains.report.facts import (
    SubmissionFacts,
    _placeholder,
    _usable_deadline,
    submission_facts,
    submission_routes,
)
from app.domains.report.opportunity import (
    OPPORTUNITY_FIELDS,
    ClassifiedFact,
    OpportunityFacts,
    opportunity_fields_needed,
    source_opportunity_facts,
    validate_opportunity,
)
from app.domains.report.presentation import korean_display, readable_source
from app.domains.report.quality import schedule_consistency_issues
from app.domains.report.schemas.request import ReportDocumentRequest
from app.domains.report.semantics import (
    field_evidence_valid,
    preserve_contact_ownership,
    source_contacts,
    source_submission_routes,
)
from app.domains.report.submission_documents import (
    SourcePart,
    SubmissionDocument,
    _form,
    _parts,
    can_reuse_submission_checklist,
    fallback_submission_documents,
    reusable_submission_documents,
    route_documents,
    source_priority,
    source_submission_documents,
    submission_documents,
    supplement_submission_documents,
)


class Evidence(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(max_length=30)
    quote: str = Field(min_length=3, max_length=700)


class SourcedText(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(
        min_length=2,
        max_length=700,
        description="Verbatim source excerpt. Do not add labels or paraphrase.",
    )
    evidence: Evidence
    display_text: str | None = Field(
        default=None,
        max_length=150,
        description="Short Korean value preserving source numbers, conditions and contacts.",
    )
    fragments: list[str] = Field(default_factory=list, max_length=4)


class SourcedAmount(SourcedText):
    display_text: str | None = Field(
        default=None, max_length=300,
        description="Short Korean sentences preserving the complete financial conditions.",
    )


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
    prevalidation_issues: list[str] = Field(default_factory=list)
    notice_kinds: list[ClassifiedFact] = Field(default_factory=list, max_length=1)
    business_types: list[ClassifiedFact] = Field(default_factory=list, max_length=1)
    business_methods: list[ClassifiedFact] = Field(default_factory=list, max_length=3)
    amounts: list[SourcedAmount] = Field(default_factory=list, max_length=3)
    participation_roles: list[SourcedText] = Field(default_factory=list, max_length=3)


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


class AmountDisplayEvidence(DisplayEvidence):
    display_text: str = Field(
        min_length=2, max_length=300,
        description="Two to four short Korean sentences, including all financial conditions.",
    )


class BriefFactsModelOutput(BaseModel):
    """Model emits each source excerpt once; legacy extraction values are derived locally."""

    model_config = ConfigDict(extra="forbid")
    applicants: list[DisplayEvidence] = Field(max_length=3)
    deadlines: list[DisplayEvidence] = Field(max_length=4)
    destinations: list[DisplayEvidence] = Field(max_length=4)
    contacts: list[DisplayEvidence] = Field(max_length=3)

    notice_kinds: list[ClassifiedFact] = Field(default_factory=list, max_length=1)
    business_types: list[ClassifiedFact] = Field(default_factory=list, max_length=1)
    business_methods: list[ClassifiedFact] = Field(default_factory=list, max_length=3)
    amounts: list[AmountDisplayEvidence] = Field(default_factory=list, max_length=3)
    participation_roles: list[DisplayEvidence] = Field(default_factory=list, max_length=3)

    def extracted(self) -> BriefOutput:
        fields = {name: getattr(self, name) for name in OPPORTUNITY_FIELDS[:3]}
        rejected = []
        for name in ("applicants", "deadlines", "destinations", "contacts",
                     "amounts", "participation_roles"):
            fields[name] = []
            for item in getattr(self, name):
                raw = " ".join(item.fragments) if item.fragments else item.evidence.quote
                raw = " ".join(raw.split())
                choice_table = name == "applicants" and checkbox_applicants(raw) is not None
                limit = 700 if choice_table or name in {"amounts", "participation_roles"} else 150
                if not 2 <= len(raw) <= limit:
                    rejected.append(f"{FACT_FIELDS.get(name, name)}:oversized_model_excerpt")
                    continue
                value_type = SourcedAmount if name == "amounts" else SourcedText
                fields[name].append(value_type(text=raw, **item.model_dump()))
        return BriefOutput(**fields, documents=[], prevalidation_issues=rejected)


class BriefModelOutput(BriefFactsModelOutput):
    documents: list[RequiredDocument] = Field(max_length=15)

    def extracted(self) -> BriefOutput:
        return super().extracted().model_copy(update={"documents": self.documents})


@dataclass(frozen=True)
class BriefContext:
    payload: str
    sources: dict[str, SourcePart]
    reused_facts: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class SubmissionBrief:
    facts: SubmissionFacts
    documents: list[SubmissionDocument]
    note: str | None = None
    uses_saved_checklist: bool = False
    validation_issues: tuple[str, ...] = ()
    recovered_fields: tuple[str, ...] = ()
    opportunity: OpportunityFacts = field(default_factory=OpportunityFacts)


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
        selected = select_report_evidence(part.text, limit)
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
    reuse_checklist = can_reuse_submission_checklist(document)
    # Reused rows stay outside the model and its cache. Their current conditions
    # and collected URLs are projected again when the report is assembled.
    checklist = [] if reuse_checklist else [item.title for item in submission_documents(document)]
    reused_facts = {
        kind: value for kind, value in verified_submission_facts(document).items()
        if field_evidence_valid(kind, value, value, document)
    }
    requested_fields = [name for name, kind in FACT_FIELDS.items() if kind not in reused_facts]
    requested_fields.extend(opportunity_fields_needed(document))
    if not reuse_checklist:
        requested_fields.append("documents")
    payload = json.dumps(
        {
            "title": document.title,
            "sources": entries,
            "saved_submission_checklist": checklist,
            "reuse_submission_checklist": reuse_checklist,
            "requested_fields": requested_fields,
            "reused_facts": reused_facts,
        },
        ensure_ascii=False,
    )
    return BriefContext(payload, sources, reused_facts)


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


def _document_condition(item: RequiredDocument) -> tuple[str | None, bool]:
    """Validate condition wording independently of the already verified document."""
    condition = " ".join((item.condition or "").split())
    if condition:
        if _compact(condition) in _compact(item.evidence.quote):
            return condition, False
        # The model may join separate table bullets with a semicolon. Each whole
        # clause must still occur in the same verified quote, in source order.
        fragments = [part.strip() for part in re.split(r"[;；]", condition)]
        if 2 <= len(fragments) <= 4 and _ordered_fragments(fragments, item.evidence.quote):
            return "; ".join(fragments), False
    # A bad model condition must not erase an explicit adjacent condition.
    adjacent = re.search(
        re.escape(item.title) + r"\s*\(\s*(해당\s*시)\s*\)", item.evidence.quote
    )
    recovered = " ".join(adjacent[1].split()) if adjacent else None
    return recovered, bool(condition)


def _values(
    items: list[SourcedText], context: BriefContext, kind: str,
    document: ReportDocumentRequest | None = None, issues: list[str] | None = None,
) -> str:
    values = []

    def reject(reason):
        if issues is not None:
            issues.append(f"{kind}:{reason}")

    for item in items:
        source = context.sources.get(item.evidence.source_id)
        if not source or source_priority(source) > (1 if kind == "contact" else 0):
            reject("invalid_source")
            continue
        value = " ".join(item.text.split())
        cited = _supported(item.evidence, context)
        if item.fragments:
            if not cited or not _ordered_fragments(item.fragments, item.evidence.quote):
                reject("unsupported_fragments")
                continue
            value = " ".join(" ".join(f.split()) for f in item.fragments)
        elif _compact(value) in _compact(source.text):
            # A verbatim value is independently verifiable even if the model's
            # surrounding quotation contains an added heading or ellipsis.
            pass
        elif not (cited and _ordered_fragments(value.split(), item.evidence.quote)):
            reject("unsupported_value")
            continue
        if _placeholder(value):
            reject("placeholder")
            continue
        if kind == "applicant":
            without_headers = re.sub(r"(?:국내)?(?:주관|공동)기관(?:자격)?", "", value)
            if _placeholder(without_headers):
                reject("placeholder")
                continue
        if kind == "contact" and not re.search(r"@|\d{2,}", value):
            quote = " ".join(item.evidence.quote.split())
            if cited and len(quote) <= 150 and re.search(r"@|\d{2,}", quote):
                value = quote
        if document and not field_evidence_valid(
            kind, value, item.evidence.quote if cited else value, document, contact_source=source,
        ):
            reject("wrong_purpose")
            continue
        display = (
            korean_display(value, item.evidence.quote, item.display_text, kind=kind)
            if cited else None
        )
        if kind == "applicant" and cited and checkbox_applicants(item.evidence.quote) is not None:
            display = readable_source(item.evidence.quote, kind)
        if kind == "deadline":
            if not _usable_deadline(display or value) or re.search(
                r"제출\s*방법|제출\s*서류|문의처|붙임|동의서|이내/년|억원|선정평가|지원기간",
                value,
            ):
                reject("invalid_schedule")
                continue
        # If an attempted Korean rewrite fails checks, do not expose the broken/raw alternative.
        if item.display_text is not None and display is None:
            reject("unsafe_display")
            continue
        value = display or readable_source(value, kind)
        if value and document and not field_evidence_valid(
            kind, value, item.evidence.quote if cited else item.text, document,
            contact_source=source,
        ):
            reject("wrong_display_purpose")
            continue
        if value:
            # Split labelled roles only, never slashes inside a URL or unlabelled conditions.
            value = re.sub(r"\s+(?:—|/)\s+(?=[^:/\n]{1,30}: )", "\n", value)
        if value and value not in values:
            values.append(value)
    joined = "\n".join(values)
    limit = 600 if kind == "deadline" else 300
    if len(joined) > limit:
        reject("field_too_long")
    return joined if 0 < len(joined) <= limit else "원문 확인 필요"


def _purpose_schedule(value: str, document: ReportDocumentRequest) -> str:
    # Keep raw date spelling while finding the originating procedure. Formatting
    # first would break the quote-to-source match and lose its section heading.
    rows = re.split(r"\n(?=[가-힣A-Za-z ()·/—-]{1,50}[:：])", value)
    checked = [row for row in rows if field_evidence_valid("deadline", row, row, document)]
    return "\n".join(checked) or "원문 확인 필요"


def _readable_fact(value: str, kind: str) -> str | None:
    common_labels = {
        "destination": r"(?:신청|제출|접수)\s*(?:방법|처)",
        "contact": r"(?:관련\s*)?문의(?:처|사항)?|담당(?:자|부서)",
    }
    if kind in common_labels:
        value = re.sub(r"(?m)^(?:" + common_labels[kind] + r")\s*[:：]\s*", "", value)
    if kind in {"destination", "contact"} and "\n" in value:
        lines = list(dict.fromkeys(line.strip() for line in value.splitlines() if line.strip()))
        if not lines or len("\n".join(lines)) > 900:
            return None
        checked = [readable_source(line, kind) for line in lines]
        # Never remove a long condition while keeping only its shorter address.
        return "\n".join(checked) if all(checked) else None
    return readable_source(value, kind)


def validate_brief(
    output: BriefOutput, context: BriefContext, document: ReportDocumentRequest
) -> SubmissionBrief:
    issues: list[str] = list(output.prevalidation_issues)
    groups = {
        "applicant": output.applicants, "deadline": output.deadlines,
        "destination": output.destinations, "contact": output.contacts,
    }
    values = {
        kind: _values(items, context, kind, document, issues)
        for kind, items in groups.items()
    }
    source_routes = submission_routes(document)
    if source_routes and not field_evidence_valid(
        "destination", source_routes, source_routes, document,
    ):
        source_routes = None
    canonical_routes = source_routes
    source_routes = source_routes or source_submission_routes(document)
    if canonical_routes and not any(item.display_text is not None for item in output.destinations):
        values["destination"] = source_routes
    elif values["destination"] != "원문 확인 필요" and source_routes:
        for clause in source_routes.split(" / "):
            if re.search(r"한\s*쪽.*제외", clause) and readable_source(clause, "destination"):
                if not re.search(r"(?:한\s*쪽|일방).*제외", values["destination"]):
                    values["destination"] += "\n" + clause
    # A rejected rewrite must not prevent independently verified source recovery.
    fallback = submission_facts(document, source_only=True)
    fallback_values = vars(fallback).copy()
    fallback_values["deadline"] = _purpose_schedule(fallback_values["deadline"], document)
    for kind in ("destination", "contact"):
        value = fallback_values[kind]
        if not field_evidence_valid(kind, value, value, document):
            fallback_values[kind] = "원문 확인 필요"
    recovered_route = source_submission_routes(document)
    if recovered_route and _readable_fact(recovered_route, "destination") and (
        fallback_values["destination"] == "원문 확인 필요"
        or _compact(fallback_values["destination"]) in _compact(recovered_route)
    ):
        fallback_values["destination"] = recovered_route
    elif fallback_values["destination"] == "원문 확인 필요":
        fallback_values["destination"] = source_routes or "원문 확인 필요"
    fallback_values["contact"] = (
        fallback_values["contact"] if fallback_values["contact"] != "원문 확인 필요"
        else source_contacts(document) or "원문 확인 필요"
    )
    recovered = []
    # An accepted publication date must not suppress independently verified
    # submission deadlines when the model's other schedule rewrites were rejected.
    current_schedule = values["deadline"]
    source_schedule = _readable_fact(fallback_values["deadline"], "deadline")
    if (re.search(r"공고|게시", current_schedule)
            and not re.search(r"신청|접수|제출|마감|의견", current_schedule)
            and source_schedule
            and re.search(r"신청|접수|제출|마감|의견", source_schedule)):
        values["deadline"] = source_schedule
        recovered.append("deadline")
    for kind in groups:
        if values[kind] == "원문 확인 필요":
            replacement = _readable_fact(fallback_values[kind], kind)
            if replacement and replacement != "원문 확인 필요":
                values[kind] = replacement
                if groups[kind]:
                    recovered.append(kind)
    for kind, value in context.reused_facts.items():
        if field_evidence_valid(kind, value, value, document):
            values[kind] = value
    values["contact"] = preserve_contact_ownership(document, values["contact"])
    facts = SubmissionFacts(**values)
    issues.extend(schedule_consistency_issues(document, facts.deadline))
    opportunity = validate_opportunity(output, context, document, issues)
    quality = {
        "opportunity": opportunity,
        "validation_issues": tuple(dict.fromkeys(issues)),
        "recovered_fields": tuple(dict.fromkeys(recovered)),
    }
    saved = reusable_submission_documents(document)
    if saved is not None:
        return SubmissionBrief(facts, saved, uses_saved_checklist=True, **quality)
    documents = []
    seen = set()
    condition_needs_review = False
    for item in output.documents:
        source = context.sources.get(item.evidence.source_id)
        if not source or source_priority(source) == 2:
            issues.append("documents:invalid_source")
            continue
        if not _supported(item.evidence, context):
            issues.append("documents:unsupported_quote")
            continue
        if _compact(item.title) not in _compact(item.evidence.quote):
            issues.append("documents:unsupported_title")
            continue
        if _placeholder(item.title):
            issues.append("documents:placeholder")
            continue
        if not re.search(r"제\s*출|구\s*비|필수|신청\s*서류|계획서\s*접수", item.evidence.quote):
            if source_priority(source) != 0 or not _submission_section_contains(
                item.evidence.quote, source.text
            ):
                issues.append("documents:missing_submission_obligation")
                continue
        if re.search(
            r"제출\s*(?:불필요|면제|생략)|제출하지\s*않|참고용|선정\s*후|협약\s*후",
            item.evidence.quote,
        ):
            issues.append("documents:wrong_purpose")
            continue
        title = " ".join(item.title.split())
        condition, needs_review = _document_condition(item)
        condition_needs_review |= needs_review
        if needs_review:
            issues.append("documents:unsupported_condition")
        key = (title, condition)
        if key in seen:
            continue
        seen.add(key)
        # Keep the validated citation for display deduplication, independently of
        # the download form. Oversized citations remain distinct; never truncate
        # evidence and accidentally discard a condition in the omitted portion.
        source_name = f"{source.archive} / {source.name}" if source.archive else source.name
        evidence_source = None
        if 5 <= len(item.evidence.quote) <= 300 and len(source_name or "") <= 500:
            evidence_source = RequirementSource(
                origin="ATTACHMENT" if source.name else "NOTICE_BODY",
                attachment_name=source_name, section_title="제출서류",
                excerpt=item.evidence.quote,
            )
        form = None
        if item.form_source_id:
            source = context.sources.get(item.form_source_id)
            if source and source.name:
                # A valid id is not sufficient: the selected attachment must match this form.
                form = _form(item.title, [source])
        if not item.form_source_id:
            form = _form(item.title, list(context.sources.values()))
        documents.append(SubmissionDocument(
            title, form, condition=condition, source=evidence_source,
        ))
    note = "제출 대상·조건 확인 필요" if condition_needs_review else None
    verified = source_submission_documents(document)
    if verified:
        if not documents:
            note = "원문 표에서 확인한 서류만 표시"
        documents = supplement_submission_documents(verified, documents)
    if not documents:
        documents = route_documents(document)
        if documents:
            note = "전체 제출서류 목록은 원문 확인 필요"
    if not documents and submission_documents(document):
        note = "기존 체크리스트의 제출 근거 재확인 필요"
    quality["validation_issues"] = tuple(dict.fromkeys(issues))
    return SubmissionBrief(facts, documents, note, **quality)


def source_brief_facts(document: ReportDocumentRequest) -> SubmissionFacts:
    facts = submission_facts(document, source_only=True)
    facts = SubmissionFacts(**{**vars(facts), **verified_submission_facts(document)})
    facts = SubmissionFacts(
        **{
            key: _readable_fact(getattr(facts, key), key) or "원문 확인 필요"
            for key in ("applicant", "deadline", "destination", "contact")
        }
    )
    values = vars(facts).copy()
    values["deadline"] = _purpose_schedule(values["deadline"], document)
    for kind, recover in (("destination", source_submission_routes), ("contact", source_contacts)):
        recovered_value = _readable_fact(recover(document) or "", kind)
        if not field_evidence_valid(kind, values[kind], values[kind], document):
            values[kind] = recovered_value or "원문 확인 필요"
        elif recovered_value and _compact(values[kind]) in _compact(recovered_value):
            values[kind] = recovered_value
    values["contact"] = preserve_contact_ownership(document, values["contact"])
    facts = SubmissionFacts(**values)
    return facts


def fallback_brief(document: ReportDocumentRequest, note: str) -> SubmissionBrief:
    facts = source_brief_facts(document)
    documents, uses_saved = fallback_submission_documents(document)
    return SubmissionBrief(
        facts, documents, note, uses_saved_checklist=uses_saved,
        validation_issues=schedule_consistency_issues(document, facts.deadline),
        opportunity=source_opportunity_facts(document),
    )
