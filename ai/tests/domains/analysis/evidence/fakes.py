"""Local chat model doubles; the real LangChain agent and tools still execute."""

import asyncio
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import Field

from app.domains.analysis.evidence.agent import AnalysisEvidenceAgent, required_evidence_tools


class ScriptedEvidenceModel(BaseChatModel):
    steps: list[Any]
    seen: list[Any] = Field(default_factory=list)
    available_tools: list[str] = Field(default_factory=list)

    @property
    def _llm_type(self):
        return "scripted-evidence-model"

    def bind_tools(self, tools, **kwargs):
        self.available_tools = [tool.name for tool in tools]
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        raise AssertionError("Tests use the asynchronous agent")

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        index = len(self.seen)
        self.seen.append(messages)
        step = self.steps[min(index, len(self.steps) - 1)]
        response = step(messages) if callable(step) else step
        if asyncio.iscoroutine(response):
            response = await response
        return ChatResult(generations=[ChatGeneration(message=response)])


def calls(*names):
    return AIMessage(
        content="",
        tool_calls=[
            {"name": name, "args": {}, "id": f"call-{index}-{name}"}
            for index, name in enumerate(names)
        ],
    )


async def collect_all_evidence(context, feedback=None):
    model = ScriptedEvidenceModel(
        steps=[
            calls(*required_evidence_tools(context.document)),
            AIMessage(content="조회 완료"),
        ]
    )
    return await AnalysisEvidenceAgent(model).collect(context, feedback=feedback)


def collected_inputs(context):
    return asyncio.run(collect_all_evidence(context))


def tool_results(messages):
    return [message for message in messages if isinstance(message, ToolMessage)]
