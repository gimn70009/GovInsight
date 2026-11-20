import json
from pathlib import Path

import pytest

from app.domains.participation import focused, grounding
from app.domains.participation.schemas import Criterion, RewriteRequest, Source

CASES = json.loads(
    (
        Path(__file__).resolve().parents[4] / "test-fixtures/participation-evidence-cases.json"
    ).read_text(encoding="utf-8")
)


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_shared_evidence_cases(case):
    assert (
        grounding.rejection(
            case["text"], case["quote"], case["kind"], case["heading"], detail=case["detail"]
        )
        == case["reason"]
    )


def test_actual_project_rejects_wrong_row_and_retains_valid_parent_of_invalid_detail():
    source = Source(id="s1", group_label="신청자격", excerpt="대학이 신청 가능\n서약서 1부")
    req = RewriteRequest(
        sources=[source], criteria=[Criterion(code="BUSINESS_FORM", label="주체", category="자격")]
    )
    items = [
        focused.ExtractedCondition(
            source_id="s1",
            criterion="BUSINESS_FORM",
            kind="ELIGIBILITY",
            text="사업자등록증을 제출할 수 있어야 합니다.",
            quote="L2",
        ),
        focused.ExtractedCondition(
            source_id="s1",
            criterion="BUSINESS_FORM",
            kind="ELIGIBILITY",
            text="대학은 신청할 수 있습니다.",
            quote="L1",
            details=[
                focused.ExtractedDetail(
                    source_id="s1", text="사업자등록증으로 증명합니다.", quote="L2"
                )
            ],
        ),
    ]
    result = focused.project(
        focused.Extraction(items=items, unresolved_source_ids=[]), [source], req
    )
    assert len(result.statements) == 1 and result.statements[0].details == []
    assert result.diagnostics["DOCUMENT_QUOTE_MISMATCH"] == 1
    assert result.diagnostics["INVALID_DETAIL"] == 1


def test_referenced_criteria_keep_children_notes_and_alternatives_in_actual_input():
    excerpt = (
        "[별표 2] 기관 기준\n가. 시설\n교육시설 보유. 온라인 전용은 제외\n"
        "나. 인력\n강사 2명 또는 자격을 모두 갖춘 1명\n1) 영상 활용 시 강사를 두지 않을 수 있음"
    )
    source = Source(id="u1", group_label="[자격 참조 원문] 3. 신청요건", excerpt=excerpt)
    req = RewriteRequest(
        sources=[source],
        criteria=[Criterion(code=code, label=code, category="자격") for code in focused.CODES],
    )
    assert focused.select_sources(req)[0].excerpt == excerpt
