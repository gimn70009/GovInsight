"""Require bounded model-selected recovery when local checks find unread evidence."""

import json

from langchain.agents.middleware import AgentMiddleware
from langchain_core.messages import AIMessage, SystemMessage
from langchain_core.utils.function_calling import convert_to_openai_tool

from app.domains.analysis.evidence.coverage import confirmed_ranges, unread_gaps
from app.domains.analysis.evidence.selection import range_covered
from app.domains.analysis.evidence.targeted import MAX_ADDITIONAL_CHARS, SEARCH_TOOLS

SEARCH = "search_document_evidence"
READ = "read_document_evidence"


def constrained_tool(tool, action, hints):
    """Offer a strict, current-document-only schema for the required recovery step."""
    schema = convert_to_openai_tool(tool, strict=True)
    parameters = schema["function"]["parameters"]
    properties = parameters["properties"]
    parameters["required"] = list(properties)
    parameters["additionalProperties"] = False
    for prop in properties.values():
        prop.pop("default", None)
    if action == SEARCH:
        queries = list(dict.fromkeys(hint["query"] for hint in hints))
        properties["queries"]["items"]["enum"] = queries
        properties["queries"]["minItems"] = len(queries)
        properties["queries"]["maxItems"] = len(queries)
        properties["queries"]["description"] = "제시된 원문 표현을 중복 없이 모두 포함합니다."
        properties["source_id"]["enum"] = ["all"]
        properties["offset"]["enum"] = [0]
        properties["unread_only"]["enum"] = [True]
    else:
        # One bounded read: never ask the model to invent or retype a different source offset.
        hint = hints[0]
        properties["source_id"]["enum"] = [hint["sourceId"]]
        properties["start"]["enum"] = [hint["start"]]
        properties["end"]["enum"] = [hint["end"]]
    return schema


class EvidenceRecoveryMiddleware(AgentMiddleware):
    def __init__(self, required, max_tool_calls):
        self.required = required
        self.max_tool_calls = max_tool_calls
        self.initial_gaps = None
        self.forced_calls = 0

    def pending(self, context):
        if not all(name in context.result_cache for name in self.required):
            return []
        if self.initial_gaps is None:
            self.initial_gaps = unread_gaps(
                context.document, confirmed_ranges(context.document, context.result_cache),
            )
        ranges = confirmed_ranges(
            context.document, context.result_cache, context.targeted.cache.values(),
        )
        return [
            gap for gap in self.initial_gaps
            if not range_covered(gap.start, gap.end, ranges.get(gap.source_id, []))
        ]

    def read_hints(self, context, pending):
        hints = []
        for key, content in context.targeted.cache.items():
            if json.loads(key)[0] != SEARCH:
                continue
            for hit in json.loads(content).get("matches", []):
                if hit["contextStatus"] != "READ_REQUIRED":
                    continue
                span = hit["suggestedRange"]
                if any(
                    gap.source_id == hit["sourceId"]
                    and gap.start < span["end"] and gap.end > span["start"]
                    for gap in pending
                ):
                    hints.append(dict(sourceId=hit["sourceId"],
                                      start=hit["matchStart"], end=hit["matchEnd"]))
        # A local scan is also an exact, verified location. Use it if a poor query missed it.
        if not hints:
            hints = [dict(sourceId=gap.source_id, start=gap.start, end=gap.end)
                     for gap in pending]
        return hints[:5]

    async def awrap_model_call(self, request, handler):
        context = request.runtime.context
        pending = self.pending(context)
        requests = [
            call["name"] for message in request.messages if isinstance(message, AIMessage)
            for call in message.tool_calls
        ]
        if not self.initial_gaps:
            return await handler(request)
        extra = [name for name in requests if name in SEARCH_TOOLS]
        budget = min(MAX_ADDITIONAL_CHARS, context.max_text_chars)
        action, hints = None, []
        if (
            pending and len(requests) < self.max_tool_calls and len(extra) < 2
            and context.targeted.selected_chars < budget
        ):
            if SEARCH not in requests and not extra:
                action = SEARCH
                context.recovery_candidates[:] = pending
                # Include different topics first; the model chooses literal queries/source.
                topics = set()
                for gap in pending:
                    if gap.topic not in topics:
                        hints.append(gap.hint())
                        topics.add(gap.topic)
            elif READ not in requests and SEARCH in requests:
                hints = self.read_hints(context, pending)
                if hints:
                    action = READ
        if action is None:
            # A final acknowledgement supplies no evidence. Stop locally, without another API call.
            return AIMessage(
                content="근거 조회를 종료합니다.",
                response_metadata={"evidence_control": "local_stop"},
            )
        self.forced_calls += 1
        if action == READ:
            hints = hints[:1]
        tool = next(tool for tool in request.tools if tool.name == action)
        directive = (
            "로컬 원문 범위 검사에서 미확인 핵심 구간을 찾았습니다. 이 후보는 조건의 확정값이 "
            "아닙니다. 지금은 완료하지 말고 지정된 도구를 한 번 호출하세요. 검색어·출처는 "
            "아래 후보를 참고해 선택하세요. 검색은 관련 query를 묶고 unread_only=true, "
            "offset=0을 사용하세요. 문맥 읽기는 검색 또는 로컬 검사의 "
            "sourceId/start/end를 선택하세요.\n"
            + json.dumps(dict(requiredTool=action, candidates=hints), ensure_ascii=False)
        )
        return await handler(request.override(
            tools=[constrained_tool(tool, action, hints)],
            tool_choice={"type": "function", "function": {"name": action}},
            system_message=SystemMessage(content=request.system_message.text + "\n" + directive),
            model_settings={**request.model_settings, "parallel_tool_calls": False},
        ))

    def report(self, context):
        pending = self.pending(context)
        if not self.initial_gaps:
            return None
        return dict(
            status="CHECK_REQUIRED" if pending else "CANDIDATES_RECOVERED",
            candidate_count=len(self.initial_gaps), unresolved_count=len(pending),
            unresolved_topics=sorted({gap.topic for gap in pending}),
            forced_calls=self.forced_calls,
            notice=(
                "키워드로 찾은 원문 구간만 검사했습니다. 전체 의미·조건의 완전성 검증이 아닙니다. "
                "CHECK_REQUIRED이면 누락 후보가 남아 있으므로 해당 항목은 확인 필요로 표시하고 "
                "조건 없음·자격 충족·제출서류 전체 확인으로 단정하지 마세요."
            ),
        )
