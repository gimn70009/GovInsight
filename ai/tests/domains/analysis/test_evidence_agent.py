import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from langchain_core.messages import AIMessage

from app.domains.analysis import evidence_agent, tools
from app.domains.analysis.agent import LangChainAnalysisRunner
from app.domains.analysis.evidence_agent import AnalysisEvidenceAgent, EvidenceCollectionError
from app.domains.analysis.graph import _safe_error
from app.domains.analysis.retry_policy import TIMEOUT_FEEDBACK
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.tools import AnalysisToolContext
from tests.domains.analysis.evidence_fakes import ScriptedEvidenceModel, calls, tool_results


def document(updated=False, attachments=True):
    return AnalysisDocumentRequest.model_validate(
        {
            "detectionId": 1,
            "documentId": 2,
            "versionId": 3,
            "changeType": "UPDATED_DOCUMENT" if updated else "NEW_DOCUMENT",
            "organizationName": "기관",
            "boardName": "공고",
            "title": "지원 사업",
            "originalUrl": "https://example.org/notice",
            "contentText": "첨부에 신청 자격이 있습니다."
            if attachments
            else "본문에 모든 조건이 있습니다.",
            "attachments": [
                {
                    "attachmentId": 4,
                    "fileName": "공고.pdf",
                    "extractedText": "신청 자격은 중소기업입니다.",
                }
            ]
            if attachments
            else [],
            "previousVersion": {"versionId": 2, "title": "지원 사업", "contentText": "이전 조건"}
            if updated
            else None,
            "previousAnalysis": {"summary": "이전 분석은 참고용입니다."} if updated else None,
        }
    )


@pytest.mark.parametrize("attachments", [True, False])
def test_model_reads_tool_result_then_selects_next_action(attachments):
    def decide(messages):
        results = {message.name: message.content for message in tool_results(messages)}
        if "get_document_content" not in results:
            return calls("get_document_content")
        body = json.loads(results["get_document_content"])["contentText"]
        if "첨부" in body and "get_attachment_texts" not in results:
            return calls("get_attachment_texts")
        if "get_company_profile" not in results:
            return calls("get_company_profile")
        return AIMessage(content="PRIVATE_INTERNAL_TEXT 이 내용은 분석 근거로 사용하지 않습니다.")

    model = ScriptedEvidenceModel(steps=[decide])
    context = AnalysisToolContext(document(attachments=attachments), 10_000)
    sections, used = asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    expected = ["get_document_content"]
    if attachments:
        expected.append("get_attachment_texts")
    expected.append("get_company_profile")
    assert used == expected
    assert len(model.seen) == len(expected) + 1
    assert ("get_attachment_texts" in model.available_tools) == attachments
    assert "compare_previous_version" not in model.available_tools
    assert "PRIVATE_INTERNAL_TEXT" not in "".join(sections)
    assert all(name in context.result_cache for name in used)


def test_updated_notice_selects_comparison_then_previous_analysis_and_keeps_order():
    def decide(messages):
        results = {message.name: message.content for message in tool_results(messages)}
        if not results:
            return calls("get_document_content", "get_company_profile")
        if "compare_previous_version" not in results:
            return calls("compare_previous_version")
        diff = json.loads(results["compare_previous_version"])
        assert diff["available"] is True
        assert "이전 조건" in diff["contentDiff"]
        if "get_previous_analysis" not in results:
            return calls("get_previous_analysis", "get_attachment_texts")
        return AIMessage(content="완료")

    model = ScriptedEvidenceModel(steps=[decide])
    sections, used = asyncio.run(
        AnalysisEvidenceAgent(model).collect(AnalysisToolContext(document(updated=True), 10_000))
    )
    assert used == [
        "get_document_content",
        "get_company_profile",
        "compare_previous_version",
        "get_previous_analysis",
        "get_attachment_texts",
    ]
    assert "<previous_version_diff>" in "".join(sections)
    assert "이전 분석은 참고용입니다." in "".join(sections)


def test_claiming_to_have_read_sources_does_not_count_as_tool_execution():
    model = ScriptedEvidenceModel(steps=[AIMessage(content="get_document_content 조회 완료")])
    context = AnalysisToolContext(document(), 10_000)
    with pytest.raises(EvidenceCollectionError, match="필수 근거 조회가 누락"):
        asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    assert context.result_cache == {}


def test_missing_attachment_is_rejected_even_when_other_sources_were_read():
    model = ScriptedEvidenceModel(
        steps=[calls("get_document_content", "get_company_profile"), AIMessage(content="완료")]
    )
    with pytest.raises(EvidenceCollectionError, match="get_attachment_texts"):
        asyncio.run(AnalysisEvidenceAgent(model).collect(AnalysisToolContext(document(), 10_000)))


def test_tool_batch_over_limit_is_not_executed(monkeypatch):
    monkeypatch.setattr(evidence_agent, "MAX_TOOL_CALLS", 1)
    model = ScriptedEvidenceModel(steps=[calls("get_document_content", "get_company_profile")])
    context = AnalysisToolContext(document(), 10_000)
    with pytest.raises(EvidenceCollectionError) as error:
        asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    assert type(error.value.__cause__).__name__ == "ToolCallLimitExceededError"
    assert context.result_cache == {}


def test_repeated_queries_stop_at_model_limit(monkeypatch):
    monkeypatch.setattr(evidence_agent, "MAX_MODEL_CALLS", 3)
    model = ScriptedEvidenceModel(steps=[calls("get_document_content")])
    with pytest.raises(EvidenceCollectionError) as error:
        asyncio.run(AnalysisEvidenceAgent(model).collect(AnalysisToolContext(document(), 10_000)))
    assert type(error.value.__cause__).__name__ == "ModelCallLimitExceededError"
    assert len(model.seen) == 3


def test_unavailable_tool_is_not_executed_or_recorded():
    model = ScriptedEvidenceModel(
        steps=[
            calls("compare_previous_version"),
            calls("get_document_content", "get_company_profile"),
            AIMessage(content="완료"),
        ]
    )
    context = AnalysisToolContext(document(attachments=False), 10_000)
    _, used = asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    assert used == ["get_document_content", "get_company_profile"]
    assert "compare_previous_version" not in context.result_cache
    assert tool_results(model.seen[1])[0].status == "error"


def test_tool_failure_is_sanitized_and_does_not_create_evidence(monkeypatch, caplog):
    def fail(context):
        raise RuntimeError("PRIVATE_SOURCE_CONTENT")

    monkeypatch.setattr(tools, "read_document_content", fail)
    context = AnalysisToolContext(document(), 10_000)
    with pytest.raises(EvidenceCollectionError) as error:
        asyncio.run(
            AnalysisEvidenceAgent(
                ScriptedEvidenceModel(steps=[calls("get_document_content")])
            ).collect(context)
        )
    assert "PRIVATE_SOURCE_CONTENT" not in _safe_error(error.value)
    assert "PRIVATE_SOURCE_CONTENT" not in caplog.text
    assert context.result_cache == {}


def test_agent_timeout_cancels_pending_model(monkeypatch):
    monkeypatch.setattr(evidence_agent, "EVIDENCE_TIMEOUT_SECONDS", 1.0)
    cancelled = []

    async def slow(messages):
        try:
            await asyncio.sleep(10)
        finally:
            cancelled.append(True)

    model = ScriptedEvidenceModel(steps=[slow])
    with pytest.raises(TimeoutError) as error:
        asyncio.run(AnalysisEvidenceAgent(model).collect(AnalysisToolContext(document(), 10_000)))
    assert _safe_error(error.value) == TIMEOUT_FEEDBACK
    assert cancelled == [True]


def test_outer_deadline_includes_evidence_collection():
    runner = LangChainAnalysisRunner.__new__(LangChainAnalysisRunner)
    runner._settings = SimpleNamespace(timeout_seconds=0.02, max_text_chars=1000)
    cancelled = []

    async def slow(*args, **kwargs):
        try:
            await asyncio.sleep(10)
        finally:
            cancelled.append(True)

    runner._evidence_agent = SimpleNamespace(collect=slow)
    runner._analysis_model = AsyncMock()
    with pytest.raises(TimeoutError):
        asyncio.run(runner.analyze(document()))
    assert cancelled == [True]
    runner._analysis_model.ainvoke.assert_not_called()


def test_parallel_documents_do_not_share_evidence_or_model_history():
    def decide(messages):
        if not tool_results(messages):
            return calls("get_document_content", "get_company_profile")
        return AIMessage(content="완료")

    model = ScriptedEvidenceModel(steps=[decide])
    agent = AnalysisEvidenceAgent(model)
    contexts = [
        AnalysisToolContext(
            document(attachments=False).model_copy(
                update={
                    "version_id": index,
                    "content_text": f"독립 문서 {index}",
                }
            ),
            10_000,
        )
        for index in (10, 20)
    ]

    async def run():
        return await asyncio.gather(*(agent.collect(context) for context in contexts))

    results = asyncio.run(run())
    assert "독립 문서 10" in "".join(results[0][0])
    assert "독립 문서 20" not in "".join(results[0][0])
    assert "독립 문서 20" in "".join(results[1][0])
    assert "독립 문서 10" not in "".join(results[1][0])


def test_evidence_failure_stops_before_final_analysis_and_legal_review():
    runner = LangChainAnalysisRunner.__new__(LangChainAnalysisRunner)
    runner._settings = SimpleNamespace(timeout_seconds=5, max_text_chars=1000)
    runner._evidence_agent = AnalysisEvidenceAgent(
        ScriptedEvidenceModel(steps=[AIMessage(content="자료를 확인하지 않고 종료합니다.")])
    )
    runner._analysis_model = AsyncMock()
    runner._assess_legal_risks = AsyncMock()
    with pytest.raises(EvidenceCollectionError, match="필수 근거 조회가 누락"):
        asyncio.run(runner.analyze(document()))
    runner._analysis_model.ainvoke.assert_not_called()
    runner._assess_legal_risks.assert_not_called()


def test_outer_timeout_cancels_legal_review_when_final_model_is_pending():
    from tests.domains.analysis.evidence_fakes import collect_all_evidence

    runner = LangChainAnalysisRunner.__new__(LangChainAnalysisRunner)
    runner._settings = SimpleNamespace(timeout_seconds=1.0, max_text_chars=1000)
    runner._evidence_agent = SimpleNamespace(collect=collect_all_evidence)
    cancelled = []

    async def slow(*args, **kwargs):
        try:
            await asyncio.sleep(10)
        finally:
            cancelled.append(True)

    runner._analysis_model = SimpleNamespace(ainvoke=slow)
    runner._assess_legal_risks = slow
    with pytest.raises(TimeoutError):
        asyncio.run(runner.analyze(document(attachments=False)))
    assert cancelled == [True, True]
