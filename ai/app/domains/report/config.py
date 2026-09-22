import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv


@dataclass(frozen=True)
class ReportBriefSettings:
    api_key: str = field(default="", repr=False)
    enabled: bool = True
    model: str = "gpt-5-mini"
    timeout_seconds: float = 30
    total_timeout_seconds: float = 60
    concurrency: int = 3
    max_text_chars: int = 16000
    cache_ttl_seconds: float = 86400

    @classmethod
    def from_env(cls):
        load_dotenv(Path(__file__).resolve().parents[3] / ".env")
        enabled = os.getenv("REPORT_BRIEF_ENABLED", "true").strip().lower()
        if enabled not in {"true", "false"}:
            raise ValueError("REPORT_BRIEF_ENABLED must be true or false")
        values = {
            "timeout_seconds": float(os.getenv("REPORT_BRIEF_TIMEOUT_SECONDS", "30")),
            "total_timeout_seconds": float(os.getenv("REPORT_BRIEF_TOTAL_TIMEOUT_SECONDS", "60")),
            "concurrency": int(os.getenv("REPORT_BRIEF_CONCURRENCY", "3")),
            "max_text_chars": int(os.getenv("REPORT_BRIEF_MAX_TEXT_CHARS", "16000")),
            "cache_ttl_seconds": float(os.getenv("REPORT_BRIEF_CACHE_TTL_SECONDS", "86400")),
        }
        if any(not 0 < value < float("inf") for value in values.values()):
            raise ValueError("Report brief limits must be positive finite numbers")
        return cls(
            api_key=os.getenv("OPENAI_API_KEY", "").strip(),
            enabled=enabled == "true",
            model=os.getenv("REPORT_BRIEF_MODEL", "gpt-5-mini").strip(),
            **values,
        )
