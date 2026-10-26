import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv


@dataclass(frozen=True)
class RewriteSettings:
    api_key: str = field(repr=False)
    model: str
    reasoning_effort: str

    @classmethod
    def from_env(cls):
        load_dotenv(Path(__file__).resolve().parents[3] / ".env")
        effort = os.getenv("PARTICIPATION_REWRITE_REASONING_EFFORT", "minimal").strip()
        if effort not in {"minimal", "low", "medium", "high"}:
            raise ValueError("Invalid participation rewrite reasoning effort")
        return cls(
            os.getenv("OPENAI_API_KEY", "").strip(),
            os.getenv("PARTICIPATION_REWRITE_MODEL", "gpt-5-nano").strip(),
            effort,
        )
