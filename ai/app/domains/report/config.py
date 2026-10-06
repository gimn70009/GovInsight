import math
import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv

# The backend reclaims report generation after 120 seconds. Reserve 30 seconds
# for request acceptance, rendering and result delivery (including retries).
_MAX_SAFE_TOTAL_TIMEOUT_SECONDS = 90.0


@dataclass(frozen=True)
class ReportBriefSettings:
    api_key: str = field(default="", repr=False)
    enabled: bool = True
    model: str = "gpt-5-mini"
    timeout_seconds: float = 30
    total_timeout_seconds: float = 60
    max_total_timeout_seconds: float = 90
    concurrency: int = 3
    max_text_chars: int = 16000
    cache_ttl_seconds: float = 86400

    def __post_init__(self):
        limits = (
            self.timeout_seconds, self.total_timeout_seconds, self.max_total_timeout_seconds,
            self.concurrency, self.max_text_chars, self.cache_ttl_seconds,
        )
        if any(not 0 < value < float("inf") for value in limits):
            raise ValueError("Report brief limits must be positive finite numbers")
        if self.total_timeout_seconds > self.max_total_timeout_seconds:
            raise ValueError("Report brief base timeout must not exceed the maximum timeout")
        if self.max_total_timeout_seconds > _MAX_SAFE_TOTAL_TIMEOUT_SECONDS:
            raise ValueError("Report brief maximum timeout must not exceed 90 seconds")

    def batch_timeout_seconds(self, required_model_calls: int) -> float:
        waves = math.ceil(required_model_calls / self.concurrency)
        return min(
            self.max_total_timeout_seconds,
            max(self.total_timeout_seconds, waves * self.timeout_seconds),
        )

    @classmethod
    def from_env(cls):
        load_dotenv(Path(__file__).resolve().parents[3] / ".env")
        enabled = os.getenv("REPORT_BRIEF_ENABLED", "true").strip().lower()
        if enabled not in {"true", "false"}:
            raise ValueError("REPORT_BRIEF_ENABLED must be true or false")
        values = {
            "timeout_seconds": float(os.getenv("REPORT_BRIEF_TIMEOUT_SECONDS", "30")),
            "total_timeout_seconds": float(os.getenv("REPORT_BRIEF_TOTAL_TIMEOUT_SECONDS", "60")),
            "max_total_timeout_seconds": float(
                os.getenv("REPORT_BRIEF_MAX_TOTAL_TIMEOUT_SECONDS", "90")
            ),
            "concurrency": int(os.getenv("REPORT_BRIEF_CONCURRENCY", "3")),
            "max_text_chars": int(os.getenv("REPORT_BRIEF_MAX_TEXT_CHARS", "16000")),
            "cache_ttl_seconds": float(os.getenv("REPORT_BRIEF_CACHE_TTL_SECONDS", "86400")),
        }
        return cls(
            api_key=os.getenv("OPENAI_API_KEY", "").strip(),
            enabled=enabled == "true",
            model=os.getenv("REPORT_BRIEF_MODEL", "gpt-5-mini").strip(),
            **values,
        )
