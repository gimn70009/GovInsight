"""Offline synthetic retrieval check, not an LLM factual-accuracy benchmark.

Run from ai/: python scripts/evaluate_evidence_selection.py
"""

import json
import statistics
import sys
from pathlib import Path
from time import perf_counter

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.domains.analysis.evidence.selection import select_evidence  # noqa: E402


def old_excerpt(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    marker = "\n...[길이 제한으로 중간 부분 생략]...\n"
    head = (limit - len(marker)) * 7 // 10
    tail = limit - len(marker) - head
    return text[:head] + marker + text[-tail:]


def main() -> None:
    facts = [
        "접수마감: 2026-10-12 18:00 (한국시간)",
        "지원대상: 중소기업\n다만, 체납 기업은 제외합니다.",
        "제출서류: 신청서, 사업계획서, 납세증명서",
        "지원규모: 과제당 최대 2억원, 자부담 20%",
        "제출처: https://example.go.kr/apply",
        "문의처: 사업지원팀 02-0000-0000",
    ]
    noise = "일반적인 사업 배경 설명입니다. " * 2000
    rows = []
    elapsed = []
    for index, fact in enumerate(facts):
        text = noise + "\n\n" + fact + "\n\n" + noise
        before = old_excerpt(text, 4000)
        for _ in range(10):
            start = perf_counter()
            after = select_evidence(text, 4000)
            elapsed.append((perf_counter() - start) * 1000)
        rows.append(
            {
                "case": index + 1,
                "source_chars": len(text),
                "before_chars": len(before),
                "after_chars": len(after.text),
                "before_retained": fact in before,
                "after_retained": fact in after.text,
            }
        )
    print(
        json.dumps(
            {
                "kind": "synthetic_source_retention_not_model_accuracy",
                "cases": rows,
                "mean_selection_ms": round(statistics.mean(elapsed), 2),
                "p95_selection_ms": round(sorted(elapsed)[int(len(elapsed) * 0.95) - 1], 2),
                "extra_model_calls": 0,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
