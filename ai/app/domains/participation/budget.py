"""Hard per-document request and input limits; no network token counter is needed."""

import json
import re
from dataclasses import dataclass

from openai import pydantic_function_tool

MAX_MODEL_CALLS = 2
MAX_CALL_INPUT_BYTES = 24_000
MAX_DOCUMENT_INPUT_BYTES = 48_000
MAX_SELECTED_SOURCE_BYTES = 10_000
MAX_SELECTED_SOURCES = 12
GENERATION_OUTPUT_TOKENS = 2_048
REVIEW_OUTPUT_TOKENS = 1_024

_PRIORITY = re.compile(
    r"신청\s*(?:자격|대상|요건|제한)|지원\s*(?:자격|대상|제외)|참여\s*(?:자격|제한)"
    r"|제외\s*대상|업력|중소기업|소상공인|휴[·ㆍ]?폐업|체납|부채|자본잠식|우대|가점"
)


def select_sources(request):
    """Select whole source blocks; unselected blocks remain evidence in the response."""
    ranked = sorted(
        enumerate(request.sources),
        key=lambda pair: (-len(_PRIORITY.findall(pair[1].group_label + pair[1].excerpt)), pair[0]),
    )
    selected = []
    used = 0
    for index, source in ranked:
        size = len(source.excerpt.encode("utf-8"))
        if not source.excerpt.strip() or used + size > MAX_SELECTED_SOURCE_BYTES:
            continue
        selected.append((index, source))
        used += size
        if len(selected) == MAX_SELECTED_SOURCES:
            break
    return request.model_copy(update={"sources": [s for _, s in sorted(selected)]})


class BudgetExceeded(RuntimeError):
    pass


@dataclass
class CallBudget:
    calls: int = 0
    input_bytes: int = 0

    def reserve(self, messages, schema):
        # Include system prompt, original text, draft and the SDK's strict schema.
        # UTF-8 bytes are a local size guard, not an exact tokenizer/price estimate.
        payload = {
            "messages": [{"role": m.type, "content": m.content} for m in messages],
            "response_format": pydantic_function_tool(schema)["function"],
        }
        size = len(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
        if (
            self.calls >= MAX_MODEL_CALLS
            or size > MAX_CALL_INPUT_BYTES
            or self.input_bytes + size > MAX_DOCUMENT_INPUT_BYTES
        ):
            raise BudgetExceeded("Participation request budget exhausted")
        # Failed requests consume their reservation too; there is no refund/retry loop.
        self.calls += 1
        self.input_bytes += size
        return size
