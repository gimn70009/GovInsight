import asyncio

import httpx
import pytest
from openai import APIConnectionError, APITimeoutError

from app.domains.analysis.workflow.graph import _safe_error
from app.domains.analysis.workflow.retry_policy import (
    TIMEOUT_FEEDBACK,
    is_timeout_error,
    needs_compact_retry,
)


def request():
    return httpx.Request("POST", "https://example.org/model")


@pytest.mark.parametrize(
    "error",
    [
        TimeoutError(),
        TimeoutError("named deadline exceeded PRIVATE_VALUE"),
        httpx.ReadTimeout("Read timed out PRIVATE_VALUE", request=request()),
        httpx.ConnectTimeout("PRIVATE_VALUE", request=request()),
        httpx.WriteTimeout("PRIVATE_VALUE", request=request()),
        httpx.PoolTimeout("PRIVATE_VALUE", request=request()),
        APITimeoutError(request=request()),
    ],
)
def test_every_timeout_type_maps_to_safe_feedback_and_compact_retry(error):
    assert is_timeout_error(error)
    assert _safe_error(error) == TIMEOUT_FEEDBACK
    assert needs_compact_retry("이전 분석 시도가 실패했습니다: " + _safe_error(error))
    assert "PRIVATE_VALUE" not in _safe_error(error)


def test_wrapped_transport_timeout_is_detected_without_exposing_wrapper_message():
    error = APIConnectionError(message="PRIVATE_CONNECTION", request=request())
    error.__cause__ = httpx.ReadTimeout("PRIVATE_URL", request=request())
    wrapper = RuntimeError("PRIVATE_WRAPPER")
    wrapper.__cause__ = error
    assert _safe_error(wrapper) == TIMEOUT_FEEDBACK


def test_implicit_timeout_context_and_exception_cycle_are_handled():
    wrapper = RuntimeError("wrapper")
    wrapper.__context__ = TimeoutError("nonempty")
    assert is_timeout_error(wrapper)
    wrapper.__suppress_context__ = True
    assert not is_timeout_error(wrapper)
    wrapper.__cause__ = wrapper
    assert not is_timeout_error(wrapper)


@pytest.mark.parametrize(
    "error",
    [
        ValueError("ordinary validation error"),
        RuntimeError("source text says timed out"),
        APIConnectionError(request=request()),
        asyncio.CancelledError(),
    ],
)
def test_non_timeout_errors_are_not_misclassified(error):
    assert not is_timeout_error(error)


@pytest.mark.parametrize("feedback", [None, "", "항목을 수정하세요", "timed out in source text"])
def test_regular_validation_feedback_does_not_reduce_input(feedback):
    assert not needs_compact_retry(feedback)


@pytest.mark.parametrize("feedback", ["Recursion limit reached", "TOOL CALL LIMIT reached"])
def test_existing_resource_limit_retries_remain_compact(feedback):
    assert needs_compact_retry(feedback)
