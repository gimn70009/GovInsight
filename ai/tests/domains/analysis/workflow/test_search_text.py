import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from app.domains.analysis.tasks import _attach_similarity_embeddings
from app.domains.analysis.workflow.search_text import (
    build_search_profile,
    clean_search_text,
    search_purpose,
)

CASES = json.loads(
    (Path(__file__).resolve().parents[5]
     / "backend/src/test/resources/similarity/text-cases.json").read_text(encoding="utf-8")
)


@pytest.mark.parametrize("case", CASES)
def test_search_text_contract(case):
    assert clean_search_text(case["input"]) == case["expected"]


def test_missing_purpose_uses_source_section_then_short_summary():
    missing = "원문에서 확인하지 못했습니다."
    assert search_purpose(
        missing, "1. 사업 목적 ○ 반도체 결함탐지. 2. 사업 내용", "다른 요약"
    ) == "반도체 결함탐지."
    assert search_purpose(
        missing, "사업 목적 " + missing, "반도체 검사입니다. 결함탐지입니다. 신청은 내일입니다."
    ) == "반도체 검사입니다. 결함탐지입니다."
    assert search_purpose(missing, "", missing) == ""


def test_embedding_input_omits_unknown_fields_and_keeps_negative_eligibility():
    comparison = SimpleNamespace(purpose="원문에서 확인하지 못했습니다.",
                                 eligibility="비영리기관은 신청할 수 없습니다.",
                                 required_partner="확인되지 않음")
    profile = build_search_profile("반도체 공고", "", "반도체 결함탐지를 지원합니다.", comparison)
    assert "사업 목적: 반도체 결함탐지를 지원합니다." in profile
    assert "지원 대상: 비영리기관은 신청할 수 없습니다." in profile
    assert "협력 구조" not in profile and "확인하지" not in profile
    assert len(build_search_profile("제목" * 2000, "", "내용" * 3000, comparison)) < 2600


def test_empty_purpose_does_not_spend_an_embedding_call():
    document = SimpleNamespace(version_id=1, title="공고", content_text="")
    result = SimpleNamespace(version_id=1, summary="원문에서 확인하지 못했습니다.",
                             comparison_summary=None)
    with patch("app.domains.analysis.tasks.OpenAIEmbeddings") as constructor:
        asyncio.run(_attach_similarity_embeddings([result], [document], SimpleNamespace()))
    constructor.assert_not_called()


def test_embedding_call_uses_cleaned_profile_and_preserves_model_name():
    document = SimpleNamespace(version_id=1, title="반도체", content_text="")
    result = SimpleNamespace(version_id=1, summary="반도체 결함탐지를 지원합니다.",
                             comparison_summary=None)
    settings = SimpleNamespace(api_key="test-key", embedding_model_name="test-model")
    with patch("app.domains.analysis.tasks.OpenAIEmbeddings") as constructor:
        constructor.return_value.aembed_documents = AsyncMock(return_value=[[1.0, 0.0]])
        asyncio.run(_attach_similarity_embeddings([result], [document], settings))
        sent = constructor.return_value.aembed_documents.call_args.args[0]
        assert sent == [result.similarity_profile]
    assert result.similarity_embedding == [1.0, 0.0]
    assert result.embedding_model_name == "test-model"
    assert "확인되지" not in result.similarity_profile
