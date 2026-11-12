"""Small, explicit OCR budgets; no model runtime or image renderer is loaded."""

import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv


@dataclass(frozen=True)
class VlmOcrSettings:
    api_key: str = field(default="", repr=False)
    enabled: bool = True
    model: str = "gpt-5-mini"
    max_pages_per_file: int = 2
    max_files_per_document: int = 2
    max_calls_per_document: int = 4
    timeout_seconds: int = 240
    page_timeout_seconds: int = 120
    max_output_tokens: int = 4096

    @classmethod
    def from_env(cls) -> "VlmOcrSettings":
        load_dotenv(Path(__file__).resolve().parents[4] / ".env")
        enabled = os.getenv("VLM_OCR_ENABLED", "true").strip().lower()
        if enabled not in {"true", "false"}:
            raise ValueError("invalid VLM_OCR_ENABLED")
        model = os.getenv("VLM_OCR_MODEL", "gpt-5-mini").strip()
        if not model:
            raise ValueError("invalid VLM_OCR_MODEL")
        return cls(
            api_key=os.getenv("OPENAI_API_KEY", "").strip(),
            enabled=enabled == "true",
            model=model,
            max_pages_per_file=_integer("VLM_OCR_MAX_PAGES_PER_FILE", 2, 2),
            max_files_per_document=_integer("VLM_OCR_MAX_FILES_PER_DOCUMENT", 2, 2),
            max_calls_per_document=_integer("VLM_OCR_MAX_CALLS_PER_DOCUMENT", 4, 4),
            timeout_seconds=_integer("VLM_OCR_TIMEOUT_SECONDS", 240, 600),
            page_timeout_seconds=_integer("VLM_OCR_PAGE_TIMEOUT_SECONDS", 120, 300),
            max_output_tokens=_integer("VLM_OCR_MAX_OUTPUT_TOKENS", 4096, 8192),
        )


def _integer(name: str, default: int, maximum: int) -> int:
    value = int(os.getenv(name, str(default)))
    if not 1 <= value <= maximum:
        raise ValueError(f"invalid {name}")
    return value
