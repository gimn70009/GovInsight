import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


class AnalysisConfigurationError(RuntimeError):
    pass


@dataclass(frozen=True)
class AnalysisSettings:
    api_key: str
    model_name: str
    timeout_seconds: float
    max_attempts: int
    max_tool_calls: int
    max_text_chars: int
    result_delivery_max_attempts: int

    @classmethod
    def from_env(cls) -> "AnalysisSettings":
        load_dotenv(Path(__file__).resolve().parents[3] / ".env")

        api_key = os.getenv("OPENAI_API_KEY", "").strip()
        if not api_key:
            raise AnalysisConfigurationError("OPENAI_API_KEY가 설정되지 않았습니다.")

        return cls(
            api_key=api_key,
            model_name=os.getenv("OPENAI_MODEL", "gpt-5-mini").strip(),
            timeout_seconds=_positive_float("ANALYSIS_TIMEOUT_SECONDS", 90.0),
            max_attempts=_positive_int("ANALYSIS_MAX_ATTEMPTS", 2),
            max_tool_calls=_positive_int("ANALYSIS_MAX_TOOL_CALLS", 6),
            max_text_chars=_positive_int("ANALYSIS_MAX_TEXT_CHARS", 40_000),
            result_delivery_max_attempts=_positive_int(
                "ANALYSIS_RESULT_MAX_ATTEMPTS", 3
            ),
        )


def _positive_int(name: str, default: int) -> int:
    value = int(os.getenv(name, str(default)))
    if value <= 0:
        raise AnalysisConfigurationError(f"{name}은 1 이상이어야 합니다.")
    return value


def _positive_float(name: str, default: float) -> float:
    value = float(os.getenv(name, str(default)))
    if value <= 0:
        raise AnalysisConfigurationError(f"{name}은 0보다 커야 합니다.")
    return value
