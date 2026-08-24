from typing import Literal, TypedDict

from langgraph.graph import END, START, StateGraph

from app.domains.analysis.agent import AgentAnalysis, AnalysisRunner
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.schemas.result import AnalysisDraft, DocumentAnalysisResult


class AnalysisWorkflowError(RuntimeError):
    pass


class AnalysisGraphState(TypedDict, total=False):
    document: AnalysisDocumentRequest
    attempt: int
    candidate: AgentAnalysis
    result: DocumentAnalysisResult
    error: str


class DocumentAnalysisWorkflow:
    def __init__(self, runner: AnalysisRunner, max_attempts: int) -> None:
        self._runner = runner
        self._max_attempts = max_attempts
        self._graph = self._build_graph()

    async def analyze(self, document: AnalysisDocumentRequest) -> DocumentAnalysisResult:
        state = await self._graph.ainvoke({"document": document, "attempt": 0})
        result = state.get("result")
        if result is None:
            raise AnalysisWorkflowError(state.get("error", "문서 분석에 실패했습니다."))
        return result

    def _build_graph(self):
        builder = StateGraph(AnalysisGraphState)
        builder.add_node("analyze", self._analyze_node)
        builder.add_node("validate", self._validate_node)
        builder.add_edge(START, "analyze")
        builder.add_conditional_edges(
            "analyze",
            self._route_after_analyze,
            {"validate": "validate", "retry": "analyze", "failed": END},
        )
        builder.add_conditional_edges(
            "validate",
            self._route_after_validate,
            {"completed": END, "retry": "analyze", "failed": END},
        )
        return builder.compile()

    async def _analyze_node(self, state: AnalysisGraphState) -> AnalysisGraphState:
        attempt = state.get("attempt", 0) + 1
        try:
            candidate = await self._runner.analyze(state["document"])
            return {"attempt": attempt, "candidate": candidate, "error": ""}
        except Exception as exception:
            return {"attempt": attempt, "error": _safe_error(exception)}

    def _validate_node(self, state: AnalysisGraphState) -> AnalysisGraphState:
        document = state["document"]
        candidate = state["candidate"]
        try:
            draft = AnalysisDraft.model_validate(candidate.draft.model_dump())
            result = DocumentAnalysisResult(
                detection_id=document.detection_id,
                document_id=document.document_id,
                version_id=document.version_id,
                summary=draft.summary,
                key_points=draft.key_points,
                importance=draft.importance,
                reason=draft.reason,
                eligibility=draft.eligibility,
                favorable_or_not=draft.favorable_or_not,
                proposal_direction=draft.proposal_direction,
                used_tools=candidate.used_tools,
                model_name=candidate.model_name,
            )
            return {"result": result, "error": ""}
        except Exception as exception:
            return {"error": _safe_error(exception)}

    def _route_after_analyze(
        self, state: AnalysisGraphState
    ) -> Literal["validate", "retry", "failed"]:
        if state.get("candidate") is not None and not state.get("error"):
            return "validate"
        if state["attempt"] < self._max_attempts:
            return "retry"
        return "failed"

    def _route_after_validate(
        self, state: AnalysisGraphState
    ) -> Literal["completed", "retry", "failed"]:
        if state.get("result") is not None:
            return "completed"
        if state["attempt"] < self._max_attempts:
            return "retry"
        return "failed"


def _safe_error(exception: Exception) -> str:
    message = str(exception).strip()
    return message[:500] if message else exception.__class__.__name__
