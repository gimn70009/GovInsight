import asyncio
import json
import logging

import httpx
import pytest
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, LLMResult
from openai import APITimeoutError

from app.observability.model_calls import ModelCallTrace, current_trace

SECRET = "private-prompt-and-api-key"


@pytest.fixture
def log_capture(caplog):
    logger = logging.getLogger("model_diagnostic_test")
    old_level, old_propagate = logger.level, logger.propagate
    logger.setLevel(logging.INFO)
    logger.propagate = False
    logger.addHandler(caplog.handler)
    yield logger
    logger.removeHandler(caplog.handler)
    logger.setLevel(old_level)
    logger.propagate = old_propagate


def events(caplog, event):
    return [json.loads(record.getMessage().split(" ", 1)[1])
            for record in caplog.records if record.getMessage().startswith(event + " ")]


def response():
    return LLMResult(generations=[[ChatGeneration(message=AIMessage(
        content=SECRET,
        usage_metadata={"input_tokens": 100, "output_tokens": 40, "total_tokens": 140,
                        "output_token_details": {"reasoning": 10}},
        response_metadata={
            "finish_reason": "stop",
            "headers": {"x-request-id": "req_abc123", "openai-processing-ms": "123.4",
                        "authorization": SECRET, "set-cookie": SECRET},
            "unknown": SECRET,
        },
    ))]])


def test_only_safe_counts_and_provider_identifiers_are_logged(log_capture, caplog):
    async def run():
        trace = ModelCallTrace(log_capture, "report_brief", version_id=2,
                               api_key=SECRET, prompt=SECRET)
        async with trace.observe():
            trace.on_chat_model_start({"secret": SECRET}, [[SECRET]])
            trace.on_llm_end(response())
            trace.stage("validation")

    asyncio.run(run())
    received = events(caplog, "model_call_response")[0]
    assert received["request_id"] == "req_abc123"
    assert received["provider_processing_ms"] == 123.4
    assert received["input_tokens"] == 100 and received["reasoning_tokens"] == 10
    assert events(caplog, "model_call_finish")[0]["response_received"] is True
    assert SECRET not in caplog.text
    assert "authorization" not in caplog.text and "set-cookie" not in caplog.text


def test_http_errors_log_status_and_cause_types_without_exception_body(log_capture, caplog):
    class RateLimitError(RuntimeError):
        status_code = 429
        request_id = "req_retry"

    async def run():
        trace = ModelCallTrace(log_capture, "proposal_generation")
        async with trace.observe():
            trace.stage("model_wait")
            error = RateLimitError(SECRET)
            error.__cause__ = TimeoutError(SECRET)
            trace.on_llm_error(error)
            raise error

    with pytest.raises(RateLimitError):
        asyncio.run(run())
    finish = events(caplog, "model_call_finish")[0]
    assert finish["http_status"] == 429 and finish["request_id"] == "req_retry"
    assert finish["cause_types"] == "RateLimitError,TimeoutError"
    assert finish["response_received"] is False
    assert SECRET not in caplog.text


def test_progress_stops_after_timeout_without_changing_exception(log_capture, caplog):
    async def run():
        trace = ModelCallTrace(log_capture, "report_brief")
        trace.progress_interval_seconds = .002
        with pytest.raises(TimeoutError):
            async with trace.observe():
                trace.stage("model_wait")
                async with asyncio.timeout(.025):
                    await asyncio.sleep(10)
        count = len(events(caplog, "model_call_progress"))
        assert count > 0
        await asyncio.sleep(.01)
        assert len(events(caplog, "model_call_progress")) == count
        assert current_trace() is None

    asyncio.run(run())
    finish = events(caplog, "model_call_finish")[0]
    assert finish["outcome"] == "timeout" and finish["stage"] == "model_wait"
    assert finish["response_received"] is False


def test_received_response_and_parse_failure_are_distinct(log_capture, caplog):
    async def run():
        async with ModelCallTrace(log_capture, "proposal_generation").observe() as trace:
            trace.on_chat_model_start({}, [])
            trace.on_llm_end(response())
            raise ValueError(SECRET)

    with pytest.raises(ValueError):
        asyncio.run(run())
    finish = events(caplog, "model_call_finish")[0]
    assert finish["stage"] == "response_parsing"
    assert finish["response_received"] is True and finish["outcome"] == "error"
    assert SECRET not in caplog.text


def test_concurrent_calls_keep_independent_trace_contexts(log_capture, caplog):
    async def run_one(version):
        trace = ModelCallTrace(log_capture, "report_brief", version_id=version)
        async with trace.observe():
            await asyncio.sleep(0)
            assert current_trace() is trace
            return trace.call_id

    async def run():
        ids = await asyncio.gather(run_one(1), run_one(2))
        assert len(set(ids)) == 2
        assert current_trace() is None

    asyncio.run(run())
    assert len(events(caplog, "model_call_finish")) == 2


@pytest.mark.parametrize("transport_error,expected", [
    (httpx.ConnectTimeout, "connect"), (httpx.ReadTimeout, "read"),
    (httpx.PoolTimeout, "connection_pool"),
])
def test_sdk_timeout_keeps_specific_transport_cause(log_capture, caplog, transport_error, expected):
    error = APITimeoutError(request=httpx.Request("POST", "https://example.org"))
    error.__cause__ = transport_error(SECRET)

    async def run():
        async with ModelCallTrace(log_capture, "report_brief").observe() as trace:
            trace.stage("model_wait")
            raise error

    with pytest.raises(APITimeoutError):
        asyncio.run(run())
    finish = events(caplog, "model_call_finish")[0]
    assert finish["outcome"] == "timeout"
    assert finish["timeout_kind"] == expected
    assert SECRET not in caplog.text


def test_sdk_parse_error_can_have_http_response_without_end_callback(log_capture, caplog):
    class StructuredParseError(ValueError):
        response = httpx.Response(200, headers={"x-request-id": "req_parse"})
    error = StructuredParseError(SECRET)

    async def run():
        async with ModelCallTrace(log_capture, "proposal_generation").observe() as trace:
            trace.stage("model_wait")
            raise error

    with pytest.raises(StructuredParseError):
        asyncio.run(run())
    finish = events(caplog, "model_call_finish")[0]
    assert finish["response_received"] is False
    assert finish["http_response_observed"] is True
    assert finish["http_status"] == 200 and finish["request_id"] == "req_parse"
    assert SECRET not in caplog.text
