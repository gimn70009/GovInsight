import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from app.domains.analysis.proposal_outline import heading_candidates
from app.domains.analysis.proposal_scope import drafting_exclusion
from app.domains.analysis.proposal_writer import ProposalWriter, ProposalWriteRequest
from tests.domains.analysis.test_proposal_reliability import PROFILE, SETTINGS, selection

FIXTURES = Path(__file__).resolve().parents[4] / "test-fixtures/proposal-scope"
CASES = json.loads((FIXTURES / "cases.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_shared_scope_boundaries(case):
    text = (FIXTURES / case["file"]).read_text(encoding="utf-8") if "file" in case else case["text"]
    assert bool(drafting_exclusion(text)) == case["excluded"]


@pytest.mark.parametrize("name", ["notice", "administrative"])
def test_known_non_template_never_reads_cache_settings_or_calls_model(name):
    request = ProposalWriteRequest(
        title="공고",
        notice_text="",
        file_name="신청서.hwpx",
        template_text=(FIXTURES / f"{name}.txt").read_text(encoding="utf-8"),
    )
    writer = ProposalWriter()
    with (
        patch("app.domains.analysis.proposal_writer.AnalysisSettings.from_env") as settings,
        patch("app.domains.analysis.proposal_writer.ChatOpenAI") as model,
    ):
        writer.cache = None  # A rejected input must not enter the cache path.
        result = asyncio.run(writer.write(request))
        assert result.status == "NEEDS_TEMPLATE"
        assert result.sections == []
        assert "대상이 아닙니다" in result.message
        assert (
            asyncio.run(writer._compose(request, PROFILE, SETTINGS, "2026-09-10")).status
            == "NEEDS_TEMPLATE"
        )
        settings.assert_not_called()
        model.assert_not_called()


def test_negative_template_decision_is_respected_with_full_document_context():
    text = "지원 내용\n기업 기술협력 지원\n지원 절차\n파트너 리스트 작성"
    request = ProposalWriteRequest(
        title="공고", notice_text="", file_name="별첨.hwpx", template_text=text
    )
    invoke = AsyncMock(side_effect=[selection(is_template=False), selection(1)])
    model = SimpleNamespace(with_structured_output=lambda schema: SimpleNamespace(ainvoke=invoke))
    outline, calls = asyncio.run(ProposalWriter()._select_outline(model, request))
    assert not outline
    assert calls == invoke.await_count == 1
    payload = json.loads(invoke.call_args.args[0][1][1])
    assert payload["document_text"] == text
    assert payload["heading_candidates"]


def test_technical_topic_is_not_mistaken_for_a_writing_instruction():
    candidates = heading_candidates("평가 기준\n협력 분야의 기술적 우수성")
    assert not candidates[0]["has_writing_guidance"]
