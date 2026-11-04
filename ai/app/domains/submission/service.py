"""One bounded semantic extraction; persistence/single-flight are owned by Spring."""

import asyncio
import logging
import os
import re
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv

from app.core.openai_models import ChatOpenAI
from app.domains.report.evidence import select_report_evidence
from app.domains.submission.prompts import PROMPT
from app.domains.submission.schemas import Note, NotesDraft, NotesRequest, NotesResponse

logger = logging.getLogger(__name__)
_slots = asyncio.Semaphore(2)
_NUMBER = re.compile(r"\d+(?:[.,]\d+)*")
_MEASURE = re.compile(
    r"\d+(?:[.,]\d+)*\s*(?:개월|시간|천원|만원|억원|년|월|일|시|분|부|매|개|건|원|쪽|%|MB|KB|GB)",
    re.I,
)
_BOUND = re.compile(r"이상|이하|미만|초과|이내|이전|이후")
_NEGATIVE = re.compile(r"불가|불허|금지|않|없|면제|생략|제외|불필요|무효|미인정")
_EXCEPTION = re.compile(r"다만|경우|한하여|한해|해당|제외|면제|예외")


@dataclass(frozen=True)
class NotesSettings:
    api_key: str = field(default="", repr=False)
    model: str = "gpt-5-mini"
    enabled: bool = True
    timeout: float = 45.0

    @classmethod
    def from_env(cls):
        load_dotenv(Path(__file__).resolve().parents[3] / ".env")
        return cls(
            api_key=os.getenv("OPENAI_API_KEY", "").strip(),
            model=os.getenv("SUBMISSION_NOTES_MODEL", "gpt-5-mini").strip(),
            enabled=os.getenv("SUBMISSION_NOTES_ENABLED", "true").lower() == "true",
        )


def valid_note(note: Note, request: NotesRequest) -> bool:
    sources = {s.id: s.text for s in request.sources}
    source = sources.get(note.sourceId, "")
    if note.quote not in source or note.scopeQuote not in source:
        return False
    text = note.text
    if (
        text != text.strip()
        or not text.endswith(("다.", "요."))
        or re.search(r"[\n\r\t�⏎□■▣]|<[^>]+>|^[*#•※]", text)
        or any(unicodedata.category(c) in {"Co", "Cs", "Cc"} for c in text)
    ):
        return False

    def numbers(value):
        return Counter(n.replace(",", "") for n in _NUMBER.findall(value))

    if numbers(note.quote) != numbers(text):
        return False

    def measures(value):
        return sorted(re.sub(r"\s|,", "", m).lower() for m in _MEASURE.findall(value))

    if measures(note.quote) != measures(text):
        return False
    if set(_BOUND.findall(note.quote)) != set(_BOUND.findall(text)):
        return False
    if bool(_NEGATIVE.search(note.quote)) != bool(_NEGATIVE.search(text)):
        return False
    if _EXCEPTION.search(note.quote) and not _EXCEPTION.search(text):
        return False
    if note.scope == "DOCUMENT":
        doc = next((d for d in request.documents if d.id == note.documentId), None)
        peers = [
            d
            for d in request.documents
            if doc and d.sourceId == doc.sourceId and d.title == doc.title
        ]
        distinct_row = len(peers) < 2 or (
            doc.excerpt in note.scopeQuote
            and not any(d.id != doc.id and d.excerpt in note.scopeQuote for d in peers)
        )
        return (
            distinct_row
            and doc is not None
            and doc.sourceId == note.sourceId
            and note.appliesTo is None
            and bool(doc.excerpt)
            and doc.excerpt in source
            and (doc.title in note.scopeQuote or doc.excerpt in note.scopeQuote)
        )
    if note.documentId is not None:
        return False
    if note.scope == "COMMON":
        return note.appliesTo is None
    return bool(note.appliesTo and note.appliesTo in note.scopeQuote)


def context(request: NotesRequest) -> tuple[NotesRequest, bool]:
    # Reuse report evidence selection: preserve complete clauses and adjacent exceptions.
    remaining = 32000
    sources = []
    incomplete = request.incomplete
    ordered = sorted(request.sources, key=lambda s: len(s.text))
    for index, source in enumerate(ordered):
        budget = remaining // (len(ordered) - index)
        selected = select_report_evidence(source.text, budget)
        incomplete |= selected.truncated
        if selected.text.strip():
            sources.append(source.model_copy(update={"text": selected.text}))
            remaining -= len(selected.text)
    ids = {s.id for s in sources}
    documents = [d for d in request.documents if d.sourceId in ids]
    return NotesRequest(sources=sources, documents=documents, incomplete=incomplete), incomplete


async def extract_notes(request: NotesRequest, *, settings=None, runner=None) -> NotesResponse:
    settings = settings or NotesSettings.from_env()
    selected, incomplete = context(request)
    if not selected.sources:
        return NotesResponse(status="PARTIAL" if incomplete else "READY")
    if not settings.enabled or (runner is None and not settings.api_key):
        return NotesResponse()
    try:
        async with asyncio.timeout(settings.timeout):
            async with _slots:
                if runner is None:
                    runner = ChatOpenAI(
                        model=settings.model,
                        api_key=settings.api_key,
                        timeout=settings.timeout,
                        max_retries=0,
                        reasoning_effort="low",
                        max_tokens=6000,
                    ).with_structured_output(NotesDraft, method="json_schema", strict=True)
                draft = await runner.ainvoke(
                    [
                        ("system", PROMPT),
                        ("human", selected.model_dump_json()),
                    ]
                )
        if not isinstance(draft, NotesDraft):
            draft = NotesDraft.model_validate(draft)
        notes = []
        seen = set()
        for note in draft.notes:
            # Verify against the full original AND the exact context seen by the model.
            if not valid_note(note, request) or not valid_note(note, selected):
                incomplete = True
                continue
            identity = (note.text, note.scope, note.documentId, note.appliesTo, note.sourceId)
            if identity not in seen:
                notes.append(note)
                seen.add(identity)
        status = "PARTIAL" if incomplete or not draft.complete else "READY"
        logger.info(
            "Submission notes finished status=%s accepted=%s rejected=%s",
            status,
            len(notes),
            len(draft.notes) - len(notes),
        )
        return NotesResponse(notes=notes, status=status)
    except Exception as exc:
        logger.warning("Submission notes unavailable error_type=%s", type(exc).__name__)
        return NotesResponse()
