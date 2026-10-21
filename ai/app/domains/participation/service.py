import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from typing import TypedDict

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph import END, START, StateGraph

from app.core.openai_models import ChatOpenAI

from .config import RewriteSettings
from .consolidation import consolidate
from .preprocess import preprocess
from .prompts import MULTI_SCOPE, REWRITE, VERIFY
from .schemas import Draft, Review, RewriteRequest, RewriteResponse
from .validation import validate

MAX_SOURCE_CHARS = 64000
TOTAL_TIMEOUT = 370
CALL_TIMEOUT = 90
_semaphore = asyncio.Semaphore(4)
logger = logging.getLogger(__name__)
DOCUMENT_TIMEOUT = 600
MAX_BATCHES = 24
BATCH_SOURCES = 8
BATCH_CHARS = 4000


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
        model = ChatOpenAI(
            model=settings.model,
            api_key=settings.api_key,
            reasoning_effort=settings.reasoning_effort,
            timeout=CALL_TIMEOUT,
            max_retries=0,
            max_tokens=20000,
        )

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
                "rows": [row.model_dump(exclude={"criteria"}) for row in rows],
                "business_types": types,
                "catalog": [c.model_dump() for c in request.criteria],
            },
        }

    async def generate(state):
        retry_ids = set(state.get("retry_ids", [s.id for s in request.sources]))
        previous = state.get("draft")
        payload = {
            **state["payload"],
            "sources": [s for s in state["payload"]["sources"] if s["id"] in retry_ids],
            "rows": [r for r in state["payload"]["rows"] if r["source_id"] in retry_ids],
            "errors": state.get("errors", {}),
            "previous_draft": previous.model_dump() if previous else None,
            "accepted_statements": [s.model_dump() for s in retained],
        }
        unavailable = False
        try:
            async with _semaphore:
                async with asyncio.timeout(CALL_TIMEOUT):
                    draft = await model.with_structured_output(
                        Draft, method="json_schema", strict=True
                    ).ainvoke(
                        [
                            SystemMessage(
                                content=REWRITE + (MULTI_SCOPE if len(state["types"]) > 1 else "")
                            ),
                            HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
                        ]
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
                async with _semaphore:
                    async with asyncio.timeout(CALL_TIMEOUT):
                        review = await model.with_structured_output(
                            Review, method="json_schema", strict=True
                        ).ainvoke(
                            [
                                SystemMessage(
                                    content=REWRITE
                                    + "\n\n"
                                    + VERIFY
                                    + (MULTI_SCOPE if len(state["types"]) > 1 else "")
                                ),
                                HumanMessage(
                                    content=json.dumps(
                                        {
                                            **state["active_payload"],
                                            "draft": draft.model_dump(),
                                            "accepted_statements": [
                                                s.model_dump() for s in retained
                                            ],
                                            "format_invalid_indexes": list(errors),
                                        },
                                        ensure_ascii=False,
                                    )
                                ),
                            ]
                        )
                review = Review.model_validate(review)
                uncovered = review.uncovered_source_ids
                used = {sid for statement in draft.statements for sid in statement.source_ids}
                ignored = [
                    sid
                    for sid in draft.irrelevant_source_ids
                    if sid not in uncovered and sid not in used
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
    graph.add_conditional_edges(
        "validate", lambda s: "rewrite" if s["retry_ids"] and s["attempt"] < 2 else END
    )
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
    """Publish whole, verified snapshots while later source groups are still being processed."""
    unavailable = RewriteResponse(
        statements=[], evidence_only_ids=[s.id for s in request.sources], status="UNAVAILABLE"
    )
    if sum(len(s.excerpt) for s in request.sources) > MAX_SOURCE_CHARS:
        return unavailable
    started = asyncio.get_running_loop().time()
    _, types = preprocess(request)
    batches: list[list] = []
    current: list = []
    for source in request.sources:
        # A small first group makes the first verified conditions available sooner.
        limit, chars = (4, 2000) if not batches else (BATCH_SOURCES, BATCH_CHARS)
        if current and (
            len(current) >= limit
            or sum(len(s.excerpt) for s in current) + len(source.excerpt) > chars
        ):
            batches.append(current)
            current = []
        current.append(source)
    if current:
        batches.append(current)
    if not batches:
        return RewriteResponse(statements=[], evidence_only_ids=[], status="READY")
    completed: dict[int, RewriteResponse] = {}
    queue: asyncio.Queue = asyncio.Queue()
    pending = iter(enumerate(batches[:MAX_BATCHES]))
    checked_pairs = {}
    checked_scope = {}

    async def worker():
        # Three workers leave capacity for incremental duplicate checks.
        for index, sources in pending:
            partial = request.model_copy(update={"sources": sources, "business_types": types})

            async def publish(result, index=index):
                await queue.put((index, result, False))

            try:
                result = await _rewrite_batch(partial, model, publish)
            except Exception as error:
                logger.warning("Participation batch failed: %s", type(error).__name__)
                result = unavailable.model_copy(
                    update={"evidence_only_ids": [s.id for s in sources]}
                )
            await queue.put((index, result, True))

    tasks = [asyncio.create_task(worker()) for _ in range(min(3, len(batches)))]
    latest = unavailable
    finished = set()
    try:
        async with asyncio.timeout(DOCUMENT_TIMEOUT):
            while len(finished) < min(MAX_BATCHES, len(batches)):
                events = [await queue.get()]
                while not queue.empty():
                    events.append(queue.get_nowait())
                for index, result, done in events:
                    completed[index] = result
                    if done:
                        finished.add(index)
                combined = _combine(list(completed.values()), request, types)
                checked = await _consolidate_response(
                    combined, request, model, started, checked_pairs, checked_scope
                )
                if checked.statements or not latest.statements:
                    latest = checked
                else:
                    covered = {sid for item in latest.statements for sid in item.source_ids}
                    latest = latest.model_copy(
                        update={
                            "evidence_only_ids": [
                                s.id for s in request.sources if s.id not in covered
                            ],
                            "irrelevant_source_ids": [],
                        }
                    )
                if on_progress:
                    await on_progress(latest)
                logger.info(
                    "Participation progress: batches=%s/%s statements=%s elapsed=%.1fs",
                    len(finished),
                    min(MAX_BATCHES, len(batches)),
                    len(latest.statements),
                    asyncio.get_running_loop().time() - started,
                )
    except TimeoutError:
        logger.warning("Participation document deadline reached; retaining verified progress")
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    return latest


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
    ignored = {sid for r in completed for sid in r.irrelevant_source_ids}
    covered = {sid for item in statements for sid in item.source_ids} | ignored
    return RewriteResponse(
        statements=statements,
        evidence_only_ids=[
            s.id for s in request.sources if s.id not in covered or s.id in unresolved
        ],
        irrelevant_source_ids=[s.id for s in request.sources if s.id in ignored],
        status="READY" if any(r.status == "READY" for r in completed) else "UNAVAILABLE",
    )


async def _consolidate_response(
    result, request, model, started, checked_pairs=None, checked_scope=None
):
    if len(result.statements) < 2:
        return result
    remaining = DOCUMENT_TIMEOUT - (asyncio.get_running_loop().time() - started)
    try:
        async with asyncio.timeout(max(0, remaining)):
            async with _semaphore:
                remaining = DOCUMENT_TIMEOUT - (asyncio.get_running_loop().time() - started)
                statements, unresolved = await consolidate(
                    result.statements,
                    request.criteria,
                    model,
                    min(180, remaining),
                    checked_scope=checked_scope,
                    checked_pairs=checked_pairs,
                )
    except TimeoutError:
        statements, unresolved = [], {s.id for s in request.sources}
    original_ids = {sid for s in result.statements for sid in s.source_ids}
    retained_ids = {sid for s in statements for sid in s.source_ids}
    ignored = original_ids - retained_ids - unresolved - set(result.evidence_only_ids)
    return result.model_copy(
        update={
            "statements": statements,
            "status": "UNAVAILABLE"
            if result.statements and not statements and unresolved
            else result.status,
            "irrelevant_source_ids": list(
                dict.fromkeys(result.irrelevant_source_ids + sorted(ignored))
            ),
            "evidence_only_ids": list(dict.fromkeys(result.evidence_only_ids + sorted(unresolved))),
        }
    )
