import asyncio
from unittest.mock import AsyncMock

from langchain_core.messages import AIMessage

from app.domains.analysis.legal.risks import (
    LegalRiskAssessment,
    LegalRiskCandidate,
    LegalRiskDecision,
    assess_with_repair,
    find_legal_risk_candidates,
)
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.schemas.result import LegalRiskType


def document(text):
    return AnalysisDocumentRequest.model_validate(
        {
            "detectionId": 1,
            "documentId": 1,
            "versionId": 1,
            "changeType": "NEW_DOCUMENT",
            "organizationName": "기관",
            "boardName": "공고",
            "title": "진단 공고",
            "contentText": text,
            "originalUrl": "https://example.com/notice",
            "attachments": [],
        }
    )


def decision(kind, candidate_id):
    return dict(
        type=kind,
        status="RESTRICTION_FOUND",
        candidate_id=candidate_id,
        interpretation="명시된 대상에 대한 제한 조항입니다.",
        implication="대상이 동일하면 신청에 영향을 줍니다.",
        verification="실제 신청 범위를 대조합니다.",
    )


def test_line_wraps_preserve_clause_and_exception_without_joining_bullets():
    candidates = find_legal_risk_candidates(
        document(
            "□ 협약에 따른 비밀정보는\n제3자에게 누설해서는 안 됩니다.\n"
            "다만 사전\n승인을 받은 공개는 제외합니다.\n□ 제출 서류는 별도입니다."
        )
    )
    assert len(candidates) == 1
    assert "사전 승인을 받은 공개는 제외" in candidates[0].excerpt
    assert "제출 서류" not in candidates[0].excerpt


def test_late_keyword_pair_and_research_allowance_are_not_lost():
    candidates = find_legal_risk_candidates(
        document(
            "지원 대상 안내입니다. "
            + "일반 안내 문장입니다. " * 12
            + "동일 과제의 중복 지원은 금지합니다.\n"
            + "동일 연구수당을 여러 과제에서 이중으로 지급받을 수 없습니다."
        )
    )
    assert {item.type.value for item in candidates} == {"DUPLICATE_SUPPORT", "COST_DOUBLE_COUNTING"}


def test_oversized_raw_item_does_not_discard_other_items_and_repair_keeps_ids():
    candidates = find_legal_risk_candidates(
        document("동일 과제 중복 지원은 금지합니다.\n비밀정보를 누설해서는 안 됩니다.")
    )
    valid = decision("DUPLICATE_SUPPORT", 1)
    invalid = decision("CONFIDENTIALITY", 2)
    invalid["interpretation"] = "가" * 141
    raw = AIMessage(
        content="",
        tool_calls=[
            {
                "name": "LegalRiskModelResponse",
                "id": "call_1",
                "args": {
                    "DUPLICATE_SUPPORT": valid,
                    "CONFIDENTIALITY": invalid,
                },
            }
        ],
    )
    model = AsyncMock()
    model.ainvoke.side_effect = [
        {"parsed": None, "raw": raw, "parsing_error": ValueError("overlong")},
        LegalRiskAssessment(legal_risks=[LegalRiskDecision(**decision("CONFIDENTIALITY", 2))]),
    ]
    result = asyncio.run(assess_with_repair(model, candidates))
    assert result[0].summary.startswith(valid["interpretation"])
    assert (
        next(item for item in result if item.type.value == "CONFIDENTIALITY").status
        == "RESTRICTION_FOUND"
    )
    repair_prompt = model.ainvoke.call_args_list[1].args[0]
    assert "id=2 type=CONFIDENTIALITY" in repair_prompt
    assert "id=1 type=DUPLICATE_SUPPORT" not in repair_prompt


def test_missing_source_context_is_not_retried_with_identical_input():
    candidates = find_legal_risk_candidates(document("동일 과제 중복 지원은 금지합니다."))
    model = AsyncMock()
    model.ainvoke.return_value = LegalRiskAssessment(
        legal_risks=[LegalRiskDecision(type="DUPLICATE_SUPPORT", status="ASSESSMENT_INCOMPLETE")]
    )
    result = asyncio.run(assess_with_repair(model, candidates))
    assert model.ainvoke.await_count == 1
    assert result[0].failure_reason == "CONTEXT_REQUIRED"


def test_only_truncated_candidates_need_no_model_call():
    candidates = [LegalRiskCandidate(LegalRiskType.DUPLICATE_SUPPORT, "공고", "잘린 조항", True)]
    model = AsyncMock()
    result = asyncio.run(assess_with_repair(model, candidates))
    model.ainvoke.assert_not_awaited()
    assert result[0].failure_reason == "CONTEXT_LIMIT"


def test_first_timeout_can_use_reserved_repair_budget():
    candidates = find_legal_risk_candidates(document("동일 과제 중복 지원은 금지합니다."))
    model = AsyncMock()
    model.ainvoke.side_effect = [
        TimeoutError(),
        LegalRiskAssessment(legal_risks=[LegalRiskDecision(**decision("DUPLICATE_SUPPORT", 1))]),
    ]
    result = asyncio.run(assess_with_repair(model, candidates))
    assert model.ainvoke.await_count == 2
    assert result[0].status == "RESTRICTION_FOUND"


def test_invented_sanction_is_not_published_and_is_repaired():
    candidates = find_legal_risk_candidates(document("동일 과제 중복 지원은 금지합니다."))
    wrong = decision("DUPLICATE_SUPPORT", 1)
    wrong["implication"] = "선정 후 지원이 취소될 수 있습니다."
    model = AsyncMock()
    model.ainvoke.side_effect = [
        LegalRiskAssessment(legal_risks=[LegalRiskDecision(**wrong)]),
        LegalRiskAssessment(legal_risks=[LegalRiskDecision(**decision("DUPLICATE_SUPPORT", 1))]),
    ]
    result = asyncio.run(assess_with_repair(model, candidates))
    assert "취소" not in result[0].summary
    assert model.ainvoke.await_count == 2
    assert result[0].failure_reason is None


def test_total_candidate_budget_preserves_each_risk_without_cutting_clauses():
    text = ("동일 과제 중복지원 금지. 사업비 이중계상 금지. 성과물 사용 제한. "
            "비밀정보 누설 금지. 제안서 표절 금지. ")
    text += "원문 조건 설명 " * 200
    candidates = find_legal_risk_candidates(
        document("\n".join(f"{i}번 {text}끝입니다." for i in range(6)))
    )
    assert {item.type for item in candidates} == set(LegalRiskType)
    assert sum(len(item.excerpt) for item in candidates) <= 18000
    assert all(item.excerpt.endswith("끝입니다.") for item in candidates)
    assert all(item.selection_limited for item in candidates)
