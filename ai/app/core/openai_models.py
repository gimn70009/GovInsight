"""The async OpenAI features used by GovInsight, without a local tokenizer.

LangChain still owns agent execution and callbacks. This adapter intentionally
supports non-streaming Chat Completions with Pydantic structured outputs only.
"""

import json
import math
from typing import Any

import httpx
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, convert_to_openai_messages
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.runnables import RunnableLambda
from langchain_core.utils.function_calling import convert_to_openai_tool
from openai import AsyncOpenAI, pydantic_function_tool
from pydantic import BaseModel, Field, SecretStr


class ChatOpenAI(BaseChatModel):
    """Small SDK adapter preserving the application's existing model interface."""

    model: str
    api_key: SecretStr = Field(exclude=True, repr=False)
    timeout: float = 60.0
    max_retries: int = 0
    reasoning_effort: str | None = None
    max_tokens: int | None = None
    include_response_headers: bool = False
    http_async_client: httpx.AsyncClient | None = Field(default=None, exclude=True, repr=False)

    @property
    def _llm_type(self) -> str:
        return "govinsight-openai"

    @property
    def _identifying_params(self) -> dict[str, Any]:
        return {"model_name": self.model}

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        raise NotImplementedError("GovInsight model calls must use ainvoke")

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        payload = {
            "model": self.model,
            "messages": convert_to_openai_messages(messages),
            **kwargs,
        }
        if self.reasoning_effort is not None:
            payload["reasoning_effort"] = self.reasoning_effort
        if self.max_tokens is not None:
            payload["max_completion_tokens"] = self.max_tokens
        if stop is not None:
            payload["stop"] = stop
        client = AsyncOpenAI(
            api_key=self.api_key.get_secret_value(), timeout=self.timeout,
            max_retries=self.max_retries, http_client=self.http_async_client,
        )
        try:
            raw = await client.chat.completions.with_raw_response.create(**payload)
            completion = raw.parse()
            headers = {
                key: raw.headers[key]
                for key in ("x-request-id", "openai-processing-ms")
                if key in raw.headers
            } if self.include_response_headers else {}
            return _chat_result(completion, headers)
        finally:
            # Injected clients are owned by their caller; default clients never leak.
            if self.http_async_client is None:
                await client.close()

    def bind_tools(self, tools, *, tool_choice=None, **kwargs):
        strict = kwargs.pop("strict", None)
        options = {"tools": [convert_to_openai_tool(tool, strict=strict) for tool in tools]}
        if tool_choice is not None:
            options["tool_choice"] = tool_choice
        return self.bind(**options, **kwargs)

    def with_structured_output(
        self, schema, *, method="json_schema", strict=True, include_raw=False, **kwargs,
    ):
        if method != "json_schema" or strict is not True or kwargs:
            raise ValueError("Only strict json_schema structured output is supported")
        if not isinstance(schema, type) or not issubclass(schema, BaseModel):
            raise TypeError("Structured output requires a Pydantic model")
        definition = pydantic_function_tool(schema)["function"]
        model = self.bind(response_format={
            "type": "json_schema",
            "json_schema": {
                "name": definition["name"], "strict": True,
                "schema": definition["parameters"],
            },
        })

        def parse(message):
            try:
                if message.additional_kwargs.get("refusal"):
                    raise ValueError("Model refused structured output")
                if message.response_metadata.get("finish_reason") != "stop":
                    raise ValueError("Model did not complete structured output")
                parsed = schema.model_validate_json(message.content)
            except Exception as error:
                if include_raw:
                    return {"raw": message, "parsed": None, "parsing_error": error}
                raise
            if include_raw:
                return {"raw": message, "parsed": parsed, "parsing_error": None}
            return parsed

        return model | RunnableLambda(parse)


def _chat_result(completion, headers: dict[str, str]) -> ChatResult:
    if len(completion.choices) != 1:
        raise ValueError("Expected one chat completion choice")
    choice = completion.choices[0]
    response = choice.message
    calls, invalid = [], []
    for call in response.tool_calls or []:
        try:
            arguments = json.loads(call.function.arguments)
            if not isinstance(arguments, dict):
                raise ValueError("Tool arguments must be an object")
            calls.append({"id": call.id, "name": call.function.name, "args": arguments})
        except (ValueError, TypeError):
            invalid.append({
                "id": call.id, "name": call.function.name, "args": call.function.arguments,
                "error": "Invalid tool arguments",
            })
    usage = completion.usage.model_dump() if completion.usage else {}
    message = AIMessage(
        content=response.content or "", id=completion.id,
        tool_calls=calls, invalid_tool_calls=invalid,
        additional_kwargs={"refusal": response.refusal} if response.refusal else {},
        response_metadata={
            "id": completion.id, "model_name": completion.model,
            "finish_reason": choice.finish_reason, "token_usage": usage, "headers": headers,
        },
        usage_metadata={
            "input_tokens": usage["prompt_tokens"],
            "output_tokens": usage["completion_tokens"],
            "total_tokens": usage["total_tokens"],
        } if usage else None,
    )
    return ChatResult(generations=[ChatGeneration(message=message)], llm_output={
        "model_name": completion.model, "token_usage": usage,
    })


class OpenAIEmbeddings:
    """Send bounded search profiles as text; never import or download a tokenizer."""

    def __init__(
        self, *, model: str, api_key: str, max_retries: int = 1,
        request_timeout: float = 15.0, http_async_client: httpx.AsyncClient | None = None,
    ):
        self.model = model
        self._api_key = api_key
        self.max_retries = max_retries
        self.request_timeout = request_timeout
        self.http_async_client = http_async_client

    async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
        # At most 8,000 UTF-8 bytes per profile and 256,000 per request.
        # Reject unexpectedly large inputs rather than silently dropping content.
        if any(not text.strip() or len(text.encode("utf-8")) > 8000 for text in texts):
            raise ValueError("Embedding input must contain 1 to 8000 UTF-8 bytes")
        if not texts:
            return []
        client = AsyncOpenAI(
            api_key=self._api_key, timeout=self.request_timeout,
            max_retries=self.max_retries, http_client=self.http_async_client,
        )
        vectors = []
        dimensions = None
        try:
            for offset in range(0, len(texts), 32):
                batch = texts[offset:offset + 32]
                response = await client.embeddings.create(
                    model=self.model, input=batch, encoding_format="float",
                )
                items = sorted(response.data, key=lambda item: item.index)
                if [item.index for item in items] != list(range(len(batch))):
                    raise ValueError("Embedding response does not match input indexes")
                for item in items:
                    vector = item.embedding
                    if not vector or any(not math.isfinite(value) for value in vector):
                        raise ValueError("Invalid embedding vector")
                    dimensions = dimensions or len(vector)
                    if len(vector) != dimensions:
                        raise ValueError("Inconsistent embedding dimensions")
                    vectors.append(vector)
            return vectors
        finally:
            if self.http_async_client is None:
                await client.close()
