import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.analysis.schemas.business_type import BusinessTypeAssessment
from app.domains.analysis.schemas.request import AnalysisAttachmentRequest
from app.domains.analysis.schemas.result import Favorability
from app.domains.analysis.workflow.agent import LangChainAnalysisRunner
from app.domains.analysis.workflow.business_type import reviewed_business_type
from tests.domains.analysis.evidence.fakes import collect_all_evidence
from tests.domains.analysis.workflow.test_graph import analysis, document

CASE_DIR = Path(__file__).resolve().parents[5] / "backend/src/test/resources/business-type"
CASES = [
    case
    for name in ("notice-cases.json", "climate-cases.json", "molit-cases.json")
    for case in json.loads((CASE_DIR / name).read_text(encoding="utf-8"))
]


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["id"])
def test_notice_kind_and_support_activity_share_backend_cases(case):
    attachments = [AnalysisAttachmentRequest(
        attachment_id=1, file_name="공고문.hwpx", extracted_text=case["attachmentText"],
    )] if "attachmentText" in case else []
    current = document().model_copy(update={
        "title": case["title"], "content_text": case["body"], "attachments": attachments,
    })
    candidate = BusinessTypeAssessment(
        primary_type=case["candidateType"],
        explanation=case.get("explanation", "현재 지원 활동을 확인했습니다."), details=[],
        sources=[dict(origin="NOTICE_BODY", excerpt=case["quote"])],
    ) if case["candidateType"] else None
    value, review = reviewed_business_type(candidate, current)
    assert review.reason == case["expectedAiReason"]
    assert review.notice_kind == case.get("expectedAiNature", case["expectedNature"])
    assert review.candidate_type == case["candidateType"]
    assert (value is not None) == (case["expectedAiReason"] == "VALIDATED")


def test_final_result_keeps_validation_diagnostics_when_type_is_rejected():
    runner = LangChainAnalysisRunner.__new__(LangChainAnalysisRunner)
    runner._settings = SimpleNamespace(max_text_chars=20_000, timeout_seconds=5, model_name="mock")
    runner._evidence_agent = SimpleNamespace(collect=collect_all_evidence)
    draft = analysis(Favorability.NOT_APPLICABLE).draft
    text = "추가 선정 결과를 붙임과 같이 공고합니다."
    draft.comparison_summary.business_type = BusinessTypeAssessment(
        primary_type="ADOPTION", explanation="설비 도입이 수반될 가능성이 있어 분류합니다.",
        details=[], sources=[dict(origin="NOTICE_BODY", excerpt=text)],
    )
    runner._analysis_model = AsyncMock()
    runner._analysis_model.ainvoke.return_value = draft.model_dump()
    runner._assess_legal_risks = AsyncMock(return_value=draft.comparison_summary.legal_risks)
    result = asyncio.run(runner.analyze(document().model_copy(update={
        "title": "추가 선정 공고", "content_text": text,
    }))).draft.comparison_summary.model_dump(mode="json", by_alias=True)
    assert result["businessType"] is None
    assert result["businessTypeReview"] == {
        "validatorVersion": "business-type-v4-opinion-notice", "candidateType": "ADOPTION",
        "reason": "NOTICE_RESULT", "sourceIndex": None, "noticeKind": "RESULT",
    }
    runner._analysis_model.ainvoke.assert_awaited_once()
