import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from typing import TypedDict

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph import END, START, StateGraph

from app.core.openai_models import ChatOpenAI
from app.observability.model_calls import ModelCallTrace

from .budget import (
    GENERATION_OUTPUT_TOKENS,
    REVIEW_OUTPUT_TOKENS,
    CallBudget,
    select_sources,
)
from .config import RewriteSettings
from .focused import extract as extract_focused
from .focused import is_focused
from .preprocess import preprocess
from .prompts import ECONOMY_REWRITE, ECONOMY_VERIFY, MULTI_SCOPE
from .schemas import Draft, Review, RewriteRequest, RewriteResponse
from .validation import validate

MAX_SOURCE_CHARS = 64000
TOTAL_TIMEOUT = 100
CALL_TIMEOUT = 45
_semaphore = asyncio.Semaphore(2)
logger = logging.getLogger(__name__)
DOCUMENT_TIMEOUT = 100


def merge_identical(statements):
    merged = {}
    for item in statements:
        identity = (item.criterion, item.kind, item.text)
        if identity in merged:
            previous = merged[identity]
            merged[identity] = previous.model_copy(
                update={
                    "source_ids": list(dict.fromkeys(previous.source_ids + item.source_ids)),
                    "supporting_quotes": list(
                        dict.fromkeys(previous.supporting_quotes + item.supporting_quotes)
                    )[:4],
                }
            )
        else:
            merged[identity] = item
    return list(merged.values())


class State(TypedDict, total=False):
    request: RewriteRequest
    payload: dict
    types: list[str]
    attempt: int
    draft: Draft
    errors: dict
    accepted: list
    uncovered: list[str]
    ignored: list[str]
    unavailable: bool
    retry_ids: list[str]
    active_payload: dict


async def _rewrite_batch(request: RewriteRequest, model=None, on_progress=None) -> RewriteResponse:
    unavailable = RewriteResponse(
        statements=[], evidence_only_ids=[s.id for s in request.sources], status="UNAVAILABLE"
    )
    if not request.sources:
        return RewriteResponse(statements=[], evidence_only_ids=[], status="READY")
    if sum(len(source.excerpt) for source in request.sources) > MAX_SOURCE_CHARS:
        return unavailable
    if model is None:
        settings = RewriteSettings.from_env()
        if not settings.api_key:
            return unavailable
        options = dict(
            model=settings.model, api_key=settings.api_key,
            reasoning_effort=settings.reasoning_effort, timeout=CALL_TIMEOUT,
            max_retries=0, include_response_headers=True,
        )
        model = ChatOpenAI(**options, max_tokens=GENERATION_OUTPUT_TOKENS)
        verifier = ChatOpenAI(**options, max_tokens=REVIEW_OUTPUT_TOKENS)
    else:
        verifier = model
    budget = CallBudget()

    async def invoke(target, schema, messages, operation, output_limit):
        input_bytes = budget.reserve(messages, schema)
        trace = ModelCallTrace(
            logger, operation, model=getattr(target, "model", "test"),
            output_limit=output_limit, timeout_seconds=CALL_TIMEOUT,
            source_chars=sum(len(s.excerpt) for s in request.sources),
            input_chars=sum(len(m.content) for m in messages),
        )
        logger.info(
            "Participation budget: calls=%s input_bytes=%s total_input_bytes=%s",
            budget.calls, input_bytes, budget.input_bytes,
        )
        async with trace.observe():
            trace.stage("queued")
            async with _semaphore:
                async with asyncio.timeout(CALL_TIMEOUT):
                    return await target.with_structured_output(
                        schema, method="json_schema", strict=True
                    ).ainvoke(messages, config={"callbacks": [trace]})

    last_verified = None
    retained = []
    irrelevant = set()

    async def prepare(state):
        rows, types = preprocess(state["request"])
        return {
            "types": types,
            "attempt": 0,
            "payload": {
                "sources": [s.model_dump() for s in request.sources],
                "rows": [
                    row.model_dump(include={"source_id", "group_label", "headers", "structure"})
                    for row in rows
                ],
                "business_types": types,
                "catalog": [c.model_dump() for c in request.criteria],
            },
        }

    async def generate(state):
        retry_ids = set(state.get("retry_ids", [s.id for s in request.sources]))
        payload = state["payload"]
        unavailable = False
        try:
            draft = await invoke(
                model, Draft,
                [
                    SystemMessage(
                        content=ECONOMY_REWRITE + (MULTI_SCOPE if len(state["types"]) > 1 else "")
                    ),
                    HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
                ],
                "participation_rewrite", GENERATION_OUTPUT_TOKENS,
            )
            draft = Draft.model_validate(draft)
            draft = draft.model_copy(update={"statements": merge_identical(draft.statements)})
        except (TimeoutError, Exception):
            # No source text or provider error body is logged or returned.
            unavailable = True
            draft = Draft(statements=[], unrecoverable_ids=list(retry_ids))
        return {
            "draft": draft,
            "attempt": state["attempt"] + 1,
            "unavailable": unavailable,
            "active_payload": payload,
        }

    async def check(state):
        nonlocal last_verified, retained
        draft = state["draft"]
        errors = validate(draft, request, state["types"], state["attempt"])
        accepted = []
        uncovered = []
        ignored = []
        unavailable = state["unavailable"]
        if draft.statements or draft.irrelevant_source_ids:
            try:
                review = await invoke(
                    verifier, Review,
                    [
                        SystemMessage(
                            content=ECONOMY_VERIFY
                            + (MULTI_SCOPE if len(state["types"]) > 1 else "")
                        ),
                        HumanMessage(content=json.dumps(
                            {
                                **state["active_payload"],
                                "draft": draft.model_dump(),
                                "format_invalid_indexes": list(errors),
                            }, ensure_ascii=False,
                        )),
                    ],
                    "participation_verify", REVIEW_OUTPUT_TOKENS,
                )
                review = Review.model_validate(review)
                uncovered = review.uncovered_source_ids
                used = {sid for statement in draft.statements for sid in statement.source_ids}
                ignored = [
                    sid
                    for sid in draft.irrelevant_source_ids
                    if sid in {source.id for source in request.sources}
                    and sid not in uncovered and sid not in used
                    and sid not in draft.unrecoverable_ids
                ]
                if uncovered:
                    errors[-1] = ["uncovered_sources: " + ", ".join(uncovered)]
                for index, item in enumerate(draft.statements):
                    verdicts = [v for v in review.verdicts if v.index == index]
                    if len(verdicts) != 1 or not verdicts[0].approved or verdicts[0].errors:
                        errors.setdefault(index, []).extend(
                            verdicts[0].errors if len(verdicts) == 1 else ["missing_review"]
                        )
                    if index not in errors:
                        accepted.append(item)
            except (TimeoutError, Exception):
                errors = {-1: ["verification_unavailable"]}
                unavailable = True
        else:
            errors = {-1: ["no_recoverable_statement"]}
        if draft.unrecoverable_ids:
            errors[-2] = ["unrecoverable_sources: " + ", ".join(draft.unrecoverable_ids)]
        retained = merge_identical(retained + accepted)
        irrelevant.update(ignored)
        covered = {sid for item in retained for sid in item.source_ids} | irrelevant
        rejected = {
            sid for i, item in enumerate(draft.statements) if i in errors for sid in item.source_ids
        }
        unresolved = set(draft.unrecoverable_ids) | rejected | set(uncovered)
        processed = {sid for item in accepted for sid in item.source_ids} | set(ignored)
        unresolved.update(
            s["id"] for s in state["active_payload"]["sources"] if s["id"] not in processed
        )
        retry_ids = [s.id for s in request.sources if s.id not in covered or s.id in unresolved]
        if unavailable:
            retry_ids = list(dict.fromkeys(retry_ids + state.get("retry_ids", [])))
        snapshot = RewriteResponse(
            statements=list(retained),
            evidence_only_ids=retry_ids,
            irrelevant_source_ids=sorted(irrelevant),
            status="READY" if retained or not unavailable else "UNAVAILABLE",
        )
        if retained or irrelevant:
            last_verified = snapshot
            if on_progress:
                await on_progress(snapshot)
        logger.info(
            "Participation batch review: attempt=%s accepted=%s rejected=%s unavailable=%s",
            state["attempt"],
            len(accepted),
            len(errors),
            unavailable,
        )
        return {
            "errors": errors,
            "accepted": accepted,
            "uncovered": uncovered,
            "ignored": ignored,
            "unavailable": unavailable,
            "retry_ids": retry_ids,
        }

    graph = StateGraph(State)
    graph.add_node("preprocess", prepare)
    graph.add_node("rewrite", generate)
    graph.add_node("validate", check)
    graph.add_edge(START, "preprocess")
    graph.add_edge("preprocess", "rewrite")
    graph.add_edge("rewrite", "validate")
    graph.add_edge("validate", END)
    try:
        async with asyncio.timeout(TOTAL_TIMEOUT):
            state = await graph.compile().ainvoke({"request": request}, {"recursion_limit": 8})
    except (TimeoutError, Exception):
        return last_verified or unavailable
    if state["unavailable"] and last_verified:
        return last_verified
    return RewriteResponse(
        statements=retained,
        evidence_only_ids=state["retry_ids"],
        status="UNAVAILABLE" if state["unavailable"] and not retained else "READY",
        irrelevant_source_ids=sorted(irrelevant),
    )


async def rewrite(
    request: RewriteRequest,
    model=None,
    on_progress: Callable[[RewriteResponse], Awaitable[None]] | None = None,
) -> RewriteResponse:
    """Use one source-extraction call for the four-field company conditions contract."""
    if is_focused(request):
        return await extract_focused(request, model, on_progress)
    unavailable = RewriteResponse(
        statements=[], evidence_only_ids=[s.id for s in request.sources], status="UNAVAILABLE"
    )
    if not request.sources:
        return RewriteResponse(statements=[], evidence_only_ids=[], status="READY")
    if sum(len(s.excerpt) for s in request.sources) > MAX_SOURCE_CHARS:
        return unavailable
    # Infer track names before selection so omitted blocks cannot change a condition's scope.
    _, types = preprocess(request)
    selected = select_sources(request).model_copy(update={"business_types": types})
    if not selected.sources:
        return unavailable
    try:
        async with asyncio.timeout(DOCUMENT_TIMEOUT):
            result = await _rewrite_batch(selected, model)
            result = _combine([result], request, types)
    except Exception as error:
        logger.warning("Participation document unavailable: %s", type(error).__name__)
        return unavailable
    if on_progress and result.status == "READY":
        await on_progress(result)
    return result


def _combine(completed, request, types):
    combined = Draft(
        statements=merge_identical([item for result in completed for item in result.statements]),
        unrecoverable_ids=[],
    )
    errors = validate(combined, request, types, 2)
    statements = [item for i, item in enumerate(combined.statements) if i not in errors]
    unresolved = {sid for r in completed for sid in r.evidence_only_ids}
    unresolved.update(
        sid for i, item in enumerate(combined.statements) if i in errors for sid in item.source_ids
    )
    ignored = {sid for r in completed for sid in r.irrelevant_source_ids} - unresolved
    covered = {sid for item in statements for sid in item.source_ids} | ignored
    return RewriteResponse(
        statements=statements,
        evidence_only_ids=[
            s.id for s in request.sources if s.id not in covered or s.id in unresolved
        ],
        irrelevant_source_ids=[s.id for s in request.sources if s.id in ignored],
        status="READY" if any(r.status == "READY" for r in completed) else "UNAVAILABLE",
    )
