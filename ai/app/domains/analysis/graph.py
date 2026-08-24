from typing import Literal, TypedDict

from langgraph.graph import END, START, StateGraph

from app.domains.analysis.agent import AgentAnalysis, AnalysisRunner
from app.domains.analysis.schemas.request import (
    AnalysisChangeType,
    AnalysisDocumentRequest,
)
from app.domains.analysis.schemas.result import (
    AnalysisDraft,
    DocumentAnalysisResult,
    Favorability,
)


class AnalysisWorkflowError(RuntimeError):
    pass


AnalysisPath = Literal["new", "updated", "unchanged"]


class AnalysisGraphState(TypedDict, total=False):
    document: AnalysisDocumentRequest
    path: AnalysisPath
    required_tools: list[str]
    attempt: int
    feedback: str
    candidate: AgentAnalysis | None
    result: DocumentAnalysisResult | None
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
        builder.add_node("prepare_context", self._prepare_context_node)
        builder.add_node("analyze_new", self._analyze_new_node)
        builder.add_node("analyze_updated", self._analyze_updated_node)
        builder.add_node("analyze_unchanged", self._analyze_unchanged_node)
        builder.add_node("validate_business_rules", self._validate_business_rules_node)
        builder.add_node("finalize", self._finalize_node)

        builder.add_edge(START, "prepare_context")
        builder.add_conditional_edges(
            "prepare_context",
            self._route_analysis_path,
            {
                "new": "analyze_new",
                "updated": "analyze_updated",
                "unchanged": "analyze_unchanged",
            },
        )
        for node_name in ("analyze_new", "analyze_updated", "analyze_unchanged"):
            builder.add_conditional_edges(
                node_name,
                self._route_after_analyze,
                {
                    "validate": "validate_business_rules",
                    "retry_new": "analyze_new",
                    "retry_updated": "analyze_updated",
                    "retry_unchanged": "analyze_unchanged",
                    "failed": END,
                },
            )
        builder.add_conditional_edges(
            "validate_business_rules",
            self._route_after_validate,
            {
                "completed": "finalize",
                "retry_new": "analyze_new",
                "retry_updated": "analyze_updated",
                "retry_unchanged": "analyze_unchanged",
                "failed": END,
            },
        )
        builder.add_edge("finalize", END)
        return builder.compile()

    def _prepare_context_node(self, state: AnalysisGraphState) -> AnalysisGraphState:
        document = state["document"]
        path = _analysis_path(document.change_type)
        required_tools = ["get_document_content", "get_company_profile"]
        if any(
            attachment.extracted_text and attachment.extracted_text.strip()
            for attachment in document.attachments
        ):
            required_tools.append("get_attachment_texts")
        if path == "updated" and document.previous_version is not None:
            required_tools.append("compare_previous_version")
        if path == "updated" and document.previous_analysis is not None:
            required_tools.append("get_previous_analysis")
        return {
            "path": path,
            "required_tools": required_tools,
            "feedback": "",
            "candidate": None,
            "result": None,
            "error": "",
        }

    async def _analyze_new_node(self, state: AnalysisGraphState) -> AnalysisGraphState:
        return await self._analyze_node(state)

    async def _analyze_updated_node(self, state: AnalysisGraphState) -> AnalysisGraphState:
        return await self._analyze_node(state)

    async def _analyze_unchanged_node(self, state: AnalysisGraphState) -> AnalysisGraphState:
        return await self._analyze_node(state)

    async def _analyze_node(self, state: AnalysisGraphState) -> AnalysisGraphState:
        attempt = state.get("attempt", 0) + 1
        try:
            candidate = await self._runner.analyze(
                state["document"],
                feedback=state.get("feedback") or None,
            )
            return {
                "attempt": attempt,
                "candidate": candidate,
                "result": None,
                "error": "",
            }
        except Exception as exception:
            error = _safe_error(exception)
            return {
                "attempt": attempt,
                "candidate": None,
                "result": None,
                "feedback": f"이전 분석 시도가 실패했습니다: {error}",
                "error": error,
            }

    def _validate_business_rules_node(self, state: AnalysisGraphState) -> AnalysisGraphState:
        candidate = state.get("candidate")
        if candidate is None:
            return {"error": "검증할 분석 결과가 없습니다."}

        draft = AnalysisDraft.model_validate(candidate.draft.model_dump())
        violations = _business_rule_violations(
            state["path"],
            state["required_tools"],
            candidate,
        )
        if violations:
            feedback = "다음 검증 문제를 모두 수정해서 다시 분석하세요: " + "; ".join(violations)
            return {
                "candidate": None,
                "feedback": feedback,
                "error": feedback,
            }
        return {
            "candidate": AgentAnalysis(
                draft=draft,
                used_tools=candidate.used_tools,
                model_name=candidate.model_name,
            ),
            "feedback": "",
            "error": "",
        }

    def _finalize_node(self, state: AnalysisGraphState) -> AnalysisGraphState:
        document = state["document"]
        candidate = state["candidate"]
        if candidate is None:
            return {"error": "최종 분석 결과가 없습니다."}
        draft = candidate.draft
        return {
            "result": DocumentAnalysisResult(
                detection_id=document.detection_id,
                document_id=document.document_id,
                version_id=document.version_id,
                summary=draft.summary,
                key_points=draft.key_points,
                importance=draft.importance,
                reason=draft.reason,
                eligibility=draft.eligibility,
                favorable_or_not=draft.favorable_or_not,
                proposal=draft.proposal,
                opportunity=draft.opportunity,
                used_tools=candidate.used_tools,
                model_name=candidate.model_name,
            ),
            "error": "",
        }

    def _route_analysis_path(self, state: AnalysisGraphState) -> AnalysisPath:
        return state["path"]

    def _route_after_analyze(
        self, state: AnalysisGraphState
    ) -> Literal["validate", "retry_new", "retry_updated", "retry_unchanged", "failed"]:
        if state.get("candidate") is not None and not state.get("error"):
            return "validate"
        return self._retry_route(state)

    def _route_after_validate(
        self, state: AnalysisGraphState
    ) -> Literal["completed", "retry_new", "retry_updated", "retry_unchanged", "failed"]:
        if state.get("candidate") is not None and not state.get("error"):
            return "completed"
        return self._retry_route(state)

    def _retry_route(
        self, state: AnalysisGraphState
    ) -> Literal["retry_new", "retry_updated", "retry_unchanged", "failed"]:
        if state["attempt"] >= self._max_attempts:
            return "failed"
        return {
            "new": "retry_new",
            "updated": "retry_updated",
            "unchanged": "retry_unchanged",
        }[state["path"]]


def _analysis_path(change_type: AnalysisChangeType) -> AnalysisPath:
    return {
        AnalysisChangeType.NEW_DOCUMENT: "new",
        AnalysisChangeType.UPDATED_DOCUMENT: "updated",
        AnalysisChangeType.UNCHANGED_DOCUMENT: "unchanged",
    }[change_type]


def _business_rule_violations(
    path: AnalysisPath,
    required_tools: list[str],
    candidate: AgentAnalysis,
) -> list[str]:
    violations = [
        f"필수 근거 도구 {tool_name}을 사용하지 않았습니다."
        for tool_name in required_tools
        if tool_name not in candidate.used_tools
    ]
    expected_titles = {
        "new": ["핵심 판단", "활용·추진 방안", "필요 파트너·준비사항", "즉시 실행"],
        "updated": ["변경 요약", "회사 영향", "대응 조정", "즉시 실행"],
        "unchanged": ["현재 상태", "유지할 대응", "다음 확인"],
    }[path]
    actual_titles = [section.title for section in candidate.draft.proposal.sections]
    if actual_titles != expected_titles:
        violations.append(
            "제안 섹션 제목과 순서는 " + ", ".join(expected_titles) + "이어야 합니다."
        )
    favorability = candidate.draft.favorable_or_not
    if path == "new" and favorability != Favorability.NOT_APPLICABLE:
        violations.append("신규 문서의 favorable_or_not은 NOT_APPLICABLE이어야 합니다.")
    if path == "updated" and favorability == Favorability.NOT_APPLICABLE:
        violations.append("수정 문서는 변경 유불리를 판단해야 합니다.")
    if path == "unchanged" and favorability != Favorability.NEUTRAL:
        violations.append("변경 없는 문서의 favorable_or_not은 NEUTRAL이어야 합니다.")
    return violations


def _safe_error(exception: Exception) -> str:
    message = str(exception).strip()
    return message[:500] if message else exception.__class__.__name__
