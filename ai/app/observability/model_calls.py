"""Content-free timing and allowlisted provider metadata for bounded model calls."""

import asyncio
import contextvars
import json
import logging
import math
import re
import time
from contextlib import asynccontextmanager
from uuid import uuid4

from httpx import ConnectTimeout, PoolTimeout, ReadTimeout, TimeoutException, WriteTimeout
from langchain_core.callbacks import BaseCallbackHandler
from openai import APITimeoutError

_CURRENT = contextvars.ContextVar("model_call_trace", default=None)
_CONTEXT_KEYS = {
    "run_id", "detection_id", "version_id", "model", "timeout_seconds",
    "input_chars", "source_chars", "queue_wait_seconds", "requested_fields",
    "output_limit", "input_build_seconds",
}


def current_trace():
    return _CURRENT.get()


def _number(value):
    if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value):
        return value
    return None


def _identifier(value):
    if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.:/,-]{1,160}", value):
        return value
    return None


def error_details(error: BaseException) -> dict:
    """Never serialize exception messages, bodies, request objects or full headers."""
    chain = []
    details = {}
    current = error
    seen = set()
    while current is not None and id(current) not in seen and len(chain) < 5:
        seen.add(id(current))
        chain.append(type(current).__name__)
        for error_class, kind in (
            (ConnectTimeout, "connect"), (ReadTimeout, "read"), (WriteTimeout, "write"),
            (PoolTimeout, "connection_pool"), (TimeoutException, "transport"),
            (APITimeoutError, "sdk"), (TimeoutError, "local_deadline"),
        ):
            if isinstance(current, error_class):
                # A transport cause is more specific than an SDK wrapper.
                if details.get("timeout_kind") in {None, "sdk"}:
                    details["timeout_kind"] = kind
                break
        response = getattr(current, "response", None)
        status = getattr(current, "status_code", None) or getattr(response, "status_code", None)
        if isinstance(status, int) and not isinstance(status, bool):
            details.setdefault("http_status", status)
        headers = getattr(response, "headers", None)
        header_id = headers.get("x-request-id") if hasattr(headers, "get") else None
        request_id = _identifier(getattr(current, "request_id", None) or header_id)
        if response is not None:
            details["http_response_observed"] = True
        if request_id:
            details.setdefault("request_id", request_id)
        current = current.__cause__ or current.__context__
    details["error_type"] = chain[0]
    details["cause_types"] = ",".join(chain)
    return details


def _response_details(response) -> dict:
    generations = getattr(response, "generations", None) or []
    generation = generations[0][0] if generations and generations[0] else None
    message = getattr(generation, "message", None)
    metadata = getattr(message, "response_metadata", None) or {}
    output = getattr(response, "llm_output", None) or {}
    metadata = metadata if isinstance(metadata, dict) else {}
    output = output if isinstance(output, dict) else {}
    usage = getattr(message, "usage_metadata", None) or {}
    legacy = metadata.get("token_usage") or output.get("token_usage") or {}
    usage = usage if isinstance(usage, dict) else {}
    legacy = legacy if isinstance(legacy, dict) else {}
    details = {}
    for name, old in (("input_tokens", "prompt_tokens"), ("output_tokens", "completion_tokens"),
                      ("total_tokens", "total_tokens")):
        value = _number(usage.get(name, legacy.get(old)))
        if value is not None:
            details[name] = value
    output_details = usage.get("output_token_details") or {}
    completion_details = legacy.get("completion_tokens_details") or {}
    if isinstance(output_details, dict) and isinstance(completion_details, dict):
        reasoning = _number(output_details.get(
            "reasoning", completion_details.get("reasoning_tokens"),
        ))
        if reasoning is not None:
            details["reasoning_tokens"] = reasoning
    generation_info = getattr(generation, "generation_info", None) or {}
    generation_info = generation_info if isinstance(generation_info, dict) else {}
    finish = _identifier(metadata.get("finish_reason") or generation_info.get("finish_reason"))
    if finish:
        details["finish_reason"] = finish
    headers = metadata.get("headers")
    if isinstance(headers, dict):
        request_id = _identifier(headers.get("x-request-id"))
        if request_id:
            details["request_id"] = request_id
        processing = headers.get("openai-processing-ms")
        if isinstance(processing, str) and re.fullmatch(r"\d+(?:\.\d+)?", processing):
            details["provider_processing_ms"] = float(processing)
    response_id = _identifier(metadata.get("id"))
    if response_id:
        details["response_id"] = response_id
    return details


class ModelCallTrace(BaseCallbackHandler):
    """One local call ID spans queue, provider wait, structured parsing and validation."""

    run_inline = True
    progress_interval_seconds = 15.0

    def __init__(self, logger: logging.Logger, operation: str, **context):
        self.logger = logger
        self.operation = _identifier(operation) or "model"
        self.call_id = uuid4().hex[:12]
        self.context = {}
        self.update(**context)
        self.started = None
        self.stage_started = None
        self.current_stage = "created"
        self.model_started = None
        self.response_received = False
        self.finished = False

    def update(self, **context):
        for key, value in context.items():
            if key not in _CONTEXT_KEYS:
                continue
            clean = _number(value) if isinstance(value, (int, float)) else _identifier(value)
            if clean is not None:
                self.context[key] = clean

    def _emit(self, event, *, warning=False, **fields):
        values = {
            "operation": self.operation, "call_id": self.call_id,
            **self.context, "stage": self.current_stage, **fields,
        }
        now = time.perf_counter()
        if self.started is not None:
            values["elapsed_seconds"] = round(now - self.started, 3)
        if self.stage_started is not None:
            values["stage_elapsed_seconds"] = round(now - self.stage_started, 3)
        self.logger.log(logging.WARNING if warning else logging.INFO, "%s %s",
                        event, json.dumps(values, ensure_ascii=False, sort_keys=True))

    def start(self):
        if self.started is None:
            self.started = self.stage_started = time.perf_counter()
            self._emit("model_call_start")

    def stage(self, name):
        self.start()
        name = _identifier(name) or "unknown"
        if name != self.current_stage:
            previous = self.current_stage
            duration = time.perf_counter() - self.stage_started
            self.current_stage = name
            self.stage_started = time.perf_counter()
            self._emit("model_call_stage", previous_stage=previous,
                       previous_stage_seconds=round(duration, 3))

    def finish(self, outcome="success", error=None):
        if self.finished:
            return
        self.finished = True
        self._emit("model_call_finish", warning=outcome not in {"success", "cache_hit"},
                   outcome=outcome, response_received=self.response_received,
                   **(error_details(error) if error else {}))

    def on_chat_model_start(self, serialized, messages, **kwargs):
        self.model_started = time.perf_counter()
        self.stage("model_wait")

    def on_llm_end(self, response, **kwargs):
        self.response_received = True
        elapsed = time.perf_counter() - self.model_started if self.model_started else None
        self._emit("model_call_response",
                   model_elapsed_seconds=round(elapsed, 3) if elapsed is not None else None,
                   **_response_details(response))
        self.stage("response_parsing")

    def on_llm_error(self, error, **kwargs):
        self._emit("model_call_error", warning=True, **error_details(error))

    async def _progress(self):
        while True:
            await asyncio.sleep(self.progress_interval_seconds)
            self._emit("model_call_progress", response_received=self.response_received)

    @asynccontextmanager
    async def observe(self):
        self.start()
        token = _CURRENT.set(self)
        progress = asyncio.create_task(self._progress())
        try:
            yield self
        except asyncio.CancelledError as error:
            self.finish("cancelled", error)
            raise
        except Exception as error:
            outcome = "timeout" if error_details(error).get("timeout_kind") else "error"
            self.finish(outcome, error)
            raise
        else:
            self.finish()
        finally:
            progress.cancel()
            await asyncio.gather(progress, return_exceptions=True)
            _CURRENT.reset(token)
