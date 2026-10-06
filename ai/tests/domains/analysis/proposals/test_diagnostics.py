import asyncio
import json
import logging
from types import SimpleNamespace
from uuid import uuid4

import pytest
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, LLMResult

from app.domains.analysis.proposals import drafting
from app.observability.model_calls import ModelCallTrace
from tests.domains.analysis.proposals.test_drafting import (
    BaseWorkflow,
    ProposalRunner,
    document,
    result,
)


@pytest.fixture(autouse=True)
def capture_diagnostics(caplog, monkeypatch):
    caplog.set_level(logging.INFO, logger=drafting.logger.name)
    # App startup may disable propagation before these tests run in the full suite.
    monkeypatch.setattr(drafting.logger, "propagate", False)
    drafting.logger.addHandler(caplog.handler)
    yield
    drafting.logger.removeHandler(caplog.handler)


@pytest.fixture
def traces(monkeypatch):
    recorded = []

    class RecordingTrace(ModelCallTrace):
        def __init__(self, logger, operation, **context):
            self.initial_context = context.copy()
            self.stage_names = []
            self.outcomes = []
            super().__init__(logger, operation, **context)
            recorded.append(self)

        def stage(self, name):
            self.stage_names.append(name)
            return super().stage(name)

        def finish(self, outcome="success", error=None):
            self.outcomes.append((outcome, type(error).__name__ if error else None))
            return super().finish(outcome, error)

    monkeypatch.setattr(drafting, "ModelCallTrace", RecordingTrace)
    return recorded


def runner(invoke, timeout=5):
    instance = drafting.LangChainProposalGenerationRunner.__new__(
        drafting.LangChainProposalGenerationRunner
    )
    instance._settings = SimpleNamespace(
        max_text_chars=20_000,
        proposal_timeout_seconds=timeout,
        proposal_model_name="diagnostics-model",
    )
    instance._draft_model = SimpleNamespace(ainvoke=invoke)
    return instance


def start_callback(config):
    callbacks = config["callbacks"]
    assert len(callbacks) == 1
    trace = callbacks[0]
    run_id = uuid4()
    trace.on_chat_model_start({}, [], run_id=run_id)
    return trace, run_id


def assert_private_text_absent(caplog):
    assert "PRIVATE_NOTICE_MARKER" not in caplog.text
    assert "PRIVATE_RESPONSE_MARKER" not in caplog.text
    assert "PRIVATE_ERROR_MARKER" not in caplog.text
    assert "PRIVATE_HEADER_MARKER" not in caplog.text


def test_proposal_diagnostics_trace_stages_callback_metadata_and_final_success(traces, caplog):
    caplog.set_level(logging.INFO)
    captured = {}

    async def scenario():
        draft = await ProposalRunner().generate(document(), result())

        async def invoke(prompt, *, config):
            captured["prompt"] = prompt
            trace, run_id = start_callback(config)
            raw = AIMessage(
                content="PRIVATE_RESPONSE_MARKER",
                usage_metadata={"input_tokens": 100, "output_tokens": 20, "total_tokens": 120},
                response_metadata={"headers": {
                    "x-request-id": "req-proposal-diagnostic",
                    "openai-processing-ms": "45",
                    "set-cookie": "PRIVATE_HEADER_MARKER",
                }},
            )
            trace.on_llm_end(
                LLMResult(generations=[[ChatGeneration(message=raw)]]), run_id=run_id,
            )
            return {"parsed": draft, "raw": raw, "parsing_error": None}

        source = document()
        source.content_text += " PRIVATE_NOTICE_MARKER"
        generated = await runner(invoke).generate(source, result())
        assert generated.preparation.submission_documents

    asyncio.run(scenario())
    trace = traces[0]
    assert trace.initial_context == {
        "detection_id": 1, "version_id": 3, "model": "diagnostics-model",
        "timeout_seconds": 5,
    }
    assert trace.stage_names[0] == "input_build"
    assert "model_invocation" in trace.stage_names
    assert "response_parsing" in trace.stage_names
    assert trace.stage_names[-1] == "final_validation"
    assert trace.outcomes[-1] == ("success", None)
    assert f"input_chars={len(captured['prompt'])}" in caplog.text
    assert "source_chars=" in caplog.text
    assert "req-proposal-diagnostic" in caplog.text
    events = [
        (record.getMessage().split(" ", 1)[0],
         json.loads(record.getMessage().split(" ", 1)[1]))
        for record in caplog.records
        if record.getMessage().startswith("model_call_")
    ]
    response = next(payload for event, payload in events if event == "model_call_response")
    assert response["input_tokens"] == 100
    assert response["output_tokens"] == 20
    assert response["provider_processing_ms"] == 45
    assert all(payload["detection_id"] == 1 and payload["version_id"] == 3
               for _, payload in events)
    assert len({payload["call_id"] for _, payload in events}) == 1
    assert all(payload["stage_elapsed_seconds"] >= 0 for _, payload in events)
    completed = [payload for event, payload in events if event == "model_call_finish"]
    assert len(completed) == 1 and completed[0]["response_received"] is True
    assert_private_text_absent(caplog)


def test_proposal_timeout_is_logged_without_changing_exception_or_retry_count(traces, caplog):
    caplog.set_level(logging.INFO)
    invocations = 0

    async def invoke(prompt, *, config):
        nonlocal invocations
        invocations += 1
        start_callback(config)
        await asyncio.Event().wait()

    with pytest.raises(TimeoutError):
        asyncio.run(runner(invoke, timeout=0.01).generate(document(), result()))
    assert invocations == 1
    assert traces[0].outcomes[-1][1] == "TimeoutError"
    assert "final_validation" not in traces[0].stage_names
    assert_private_text_absent(caplog)


def test_proposal_cancellation_propagates_and_records_last_stage(traces, caplog):
    caplog.set_level(logging.INFO)

    async def scenario():
        started = asyncio.Event()

        async def invoke(prompt, *, config):
            start_callback(config)
            started.set()
            await asyncio.Event().wait()

        task = asyncio.create_task(runner(invoke).generate(document(), result()))
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())
    assert traces[0].outcomes[-1][1] == "CancelledError"
    assert "final_validation" not in traces[0].stage_names
    assert_private_text_absent(caplog)


def test_structured_parsing_failure_is_distinct_and_safe_in_workflow_log(traces, caplog):
    caplog.set_level(logging.INFO)

    async def invoke(prompt, *, config):
        start_callback(config)
        return {"parsed": None, "parsing_error": ValueError("PRIVATE_ERROR_MARKER")}

    workflow = drafting.TwoStageAnalysisWorkflow(BaseWorkflow(result()), runner(invoke))
    generated = asyncio.run(workflow.analyze(document()))
    assert generated.proposal.preparation is None
    assert traces[0].stage_names[-1] == "response_parsing"
    assert traces[0].outcomes[-1][1] == "ValueError"
    assert "error_type=ValueError" in caplog.text
    assert_private_text_absent(caplog)


def test_post_model_validation_failure_is_observable_without_changing_behavior(
    traces, monkeypatch, caplog,
):
    caplog.set_level(logging.INFO)

    def fail_validation(*args, **kwargs):
        raise ValueError("PRIVATE_ERROR_MARKER")

    monkeypatch.setattr(drafting, "validate_submission_documents", fail_validation)

    async def scenario():
        draft = await ProposalRunner().generate(document(), result())

        async def invoke(prompt, *, config):
            start_callback(config)
            return draft

        with pytest.raises(ValueError, match="PRIVATE_ERROR_MARKER"):
            await runner(invoke).generate(document(), result())

    asyncio.run(scenario())
    assert traces[0].stage_names[-1] == "final_validation"
    assert traces[0].outcomes[-1][1] == "ValueError"
    assert_private_text_absent(caplog)
