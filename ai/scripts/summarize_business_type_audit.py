"""Summarize offline business-type replays; never calls a model or database."""

import argparse
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "backend/src/test/resources/business-type"
OUTPUT = ROOT / "backend/build/business-type-audit"


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def outcome(row: dict) -> str:
    return row["actualCode"] or row["actualStatus"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--current", type=Path, default=OUTPUT / "current.json")
    parser.add_argument("--output", type=Path, default=OUTPUT / "report.md")
    args = parser.parse_args()
    cases = read(FIXTURES / "cases.json")
    before = {row["id"]: row for row in read(FIXTURES / "baseline.json")["cases"]}
    after = {row["id"]: row for row in read(args.current)["cases"]}
    expected_ids = {case["id"] for case in cases}
    if set(before) != expected_ids or set(after) != expected_ids:
        raise ValueError("Baseline/current case IDs do not match the frozen evaluation set")
    for case in cases:
        for result in (before[case["id"]], after[case["id"]]):
            if any(result[key] != case[key] for key in ("expectedStatus", "expectedCode")):
                raise ValueError("Expected labels changed after the baseline was recorded")
    count_before = sum(row["passed"] for row in before.values())
    count_after = sum(row["passed"] for row in after.values())
    groups = {case["sourceGroup"] for case in cases if case["kind"].startswith("public_")}
    changed = [
        case["id"] for case in cases
        if outcome(before[case["id"]]) != outcome(after[case["id"]])
    ]
    reasons = Counter(row.get("decisionReason", "NOT_RECORDED") for row in after.values())
    lines = [
        "# 사업 유형 오프라인 재현 결과", "",
        f"기준선: b2b0b89 / 기대 결과 일치: {count_before}/{len(cases)} → "
        f"{count_after}/{len(cases)}", "",
        f"공식 공고 출처 {len(groups)}개, 원문·형식·경계 사례 {len(cases)}개입니다. "
        "같은 공고의 PDF/HWPX와 변형은 독립 공고로 세지 않습니다.", "",
        "라벨은 저장소 분류 기준과 보존된 원문을 에이전트가 대조한 결과입니다. "
        "독립적인 사람 검수나 실제 모델 호출 평가가 아니며 운영 정확도를 뜻하지 않습니다.", "",
        "| 사례 | 자료 종류 | 기대 | 수정 전 | 수정 후 | 현재 결정 / 저장값 진단 |",
        "|---|---|---|---|---|---|",
    ]
    for case in cases:
        old, new = before[case["id"]], after[case["id"]]
        label = case["expectedCode"] or case["expectedStatus"]
        lines.append(
            f"| {case['id']} | {case['kind']} | {label} | {outcome(old)} | "
            f"{outcome(new)} | {new.get('decisionReason')} / {new.get('storedReason')} |"
        )
    lines += ["", "변경 사례: " + ", ".join(changed), "", "현재 결정 사유:", ""]
    lines += [f"- `{reason}`: {count}건" for reason, count in sorted(reasons.items())]
    lines += ["", "## 출처 및 기대 판단", ""]
    for case in cases:
        source = case.get("sourceUrl")
        reference = f" [공식 공고]({source})" if source else ""
        lines.append(f"- **{case['id']}**: {case['rationale']}{reference}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"cases": len(cases), "public_notice_groups": len(groups),
                      "before": count_before, "after": count_after, "changed": changed}))


if __name__ == "__main__":
    main()
