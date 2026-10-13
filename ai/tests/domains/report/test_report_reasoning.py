"""Effort is explicit and must never reuse output from a different setting."""
import asyncio
import json
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from app.domains.report import config, enrichment
from app.domains.report.brief import (
    BriefContext,
    BriefModelOutput,
    BriefOutput,
    build_context,
    validate_brief,
)
from app.domains.report.config import ReportBriefSettings
from tests.domains.report.test_report_brief import output, request, run, sourced
from tests.domains.report.test_report_template import with_document


@pytest.mark.parametrize("effort", ["minimal", "low", "medium", "high"])
def test_effort_reaches_model_and_environment(monkeypatch, effort):
    monkeypatch.setattr(config, "load_dotenv", lambda *_: None)
    monkeypatch.setenv("REPORT_BRIEF_REASONING_EFFORT", effort.upper())
    settings = ReportBriefSettings.from_env()
    factory = Mock()
    monkeypatch.setattr(enrichment, "ChatOpenAI", factory)
    enrichment.SmallModelBriefRunner(settings)
    assert settings.reasoning_effort == effort
    assert factory.call_args.kwargs["reasoning_effort"] == effort
    assert factory.call_args.kwargs["max_tokens"] == 5000


def test_invalid_effort_is_rejected():
    with pytest.raises(ValueError, match="REPORT_BRIEF_REASONING_EFFORT"):
        ReportBriefSettings(reasoning_effort="maximum")


def test_cache_separates_efforts_and_reuses_same_effort():
    enrichment._CACHE.clear()
    try:
        runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
        settings = ReportBriefSettings(reasoning_effort="minimal")
        run(runner=runner, settings=settings)
        run(runner=runner, settings=replace(settings, reasoning_effort="medium"))
        run(runner=runner, settings=settings)
        run(runner=runner, settings=replace(settings, reasoning_effort="medium"))
        assert runner.extract.await_count == 2
    finally:
        enrichment._CACHE.clear()


def test_partial_field_schema_has_a_provider_compatible_name(monkeypatch):
    factory = Mock()
    requested = (
        "applicants", "destinations", "contacts", "amounts", "participation_roles", "documents",
    )
    model = factory.return_value
    model.with_structured_output.return_value.ainvoke = AsyncMock(return_value={
        "parsed": {field: [] for field in requested}, "parsing_error": None,
    })
    monkeypatch.setattr(enrichment, "ChatOpenAI", factory)
    runner = enrichment.SmallModelBriefRunner(ReportBriefSettings())
    original = build_context(request().documents[0], 16000)
    data = json.loads(original.payload)
    data.update(requested_fields=list(requested), reused_facts={"deadline": "stored deadline"})
    context = BriefContext(json.dumps(data), original.sources, {"deadline": "stored deadline"})
    asyncio.run(runner.extract(context))
    schema = model.with_structured_output.call_args.args[0]
    assert len(schema.__name__) <= 64
    assert set(schema.model_fields) == set(requested)
    expected = BriefModelOutput.model_fields["amounts"].annotation
    assert schema.model_fields["amounts"].annotation == expected


def test_default_uses_selected_low_effort(monkeypatch):
    monkeypatch.setattr(config, "load_dotenv", lambda *_: None)
    monkeypatch.delenv("REPORT_BRIEF_REASONING_EFFORT", raising=False)
    assert ReportBriefSettings().reasoning_effort == "low"
    assert ReportBriefSettings.from_env().reasoning_effort == "low"


def test_publication_only_model_schedule_cannot_hide_verified_language_deadlines():
    publication = "사업 공고: 2026년 10월 12일"
    source = (publication + "\n영문 연구개발계획서 접수 마감: 2027년 1월 20일\n"
              "국문 연구개발계획서 접수 마감: 2027년 1월 21일 16:00(KST)")
    document = with_document(title="국제공동 연구개발 모집 공고", contentText=source).documents[0]
    result = BriefOutput(applicants=[], deadlines=[sourced(publication)],
                         destinations=[], contacts=[], documents=[])
    brief = validate_brief(result, build_context(document, 16000), document)
    assert "영문 연구개발계획서 접수 마감" in brief.facts.deadline
    assert "국문 연구개발계획서 접수 마감" in brief.facts.deadline
    assert "16:00(KST)" in brief.facts.deadline
    assert "deadline" in brief.recovered_fields
