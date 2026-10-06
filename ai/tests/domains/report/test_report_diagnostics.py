"""Report logs must explain missing enrichment without exposing source contents."""

import asyncio
import json
import logging
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.report import enrichment
from app.domains.report.config import ReportBriefSettings
from tests.domains.report.test_report_brief import SOURCE, output, request, run
from tests.domains.report.test_report_fact_reuse import request as verified_request


@pytest.fixture(autouse=True)
def isolated_diagnostics(caplog, monkeypatch):
    enrichment._CACHE.clear()
    caplog.set_level(logging.INFO, logger=enrichment.logger.name)
    # App startup in other tests disables propagation before this module runs.
    monkeypatch.setattr(enrichment.logger, "propagate", False)
    enrichment.logger.addHandler(caplog.handler)
    yield
    enrichment.logger.removeHandler(caplog.handler)
    enrichment._CACHE.clear()


def events(caplog, name):
    prefix = name + " "
    return [
        json.loads(record.getMessage()[len(prefix):])
        for record in caplog.records
        if record.getMessage().startswith(prefix)
    ]


def multiple_request(*titles):
    req = request()
    doc = req.documents[0]
    return req.model_copy(update={
        "documents": [
            doc.model_copy(update={"version_id": index + 2, "title": title})
            for index, title in enumerate(titles)
        ],
    })


def test_success_logs_run_document_call_and_batch_counts_without_source(caplog):
    req = multiple_request("private-title-one", "private-title-two")
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    settings = replace(ReportBriefSettings(), concurrency=2)

    result = run(req, runner, settings)

    assert all(brief.note is None for brief in result.values())
    start, = events(caplog, "report_brief_batch_start")
    assert start["run_id"] == req.run_id
    assert start["document_count"] == 2
    assert start["concurrency"] == 2
    assert start["timeout_seconds"] == settings.timeout_seconds
    assert start["total_timeout_seconds"] == settings.total_timeout_seconds
    calls = events(caplog, "model_call_start")
    assert len(calls) == 2
    assert len({call["call_id"] for call in calls}) == 2
    assert {call["version_id"] for call in calls} == {2, 3}
    assert all(call["operation"] == "report_brief" for call in calls)
    assert all(call["run_id"] == req.run_id for call in calls)
    results = events(caplog, "report_brief_result")
    assert {row["version_id"] for row in results} == {2, 3}
    assert all(row["outcome"] == "model_success" for row in results)
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["model_success_count"] == 2
    assert finish["fallback_count"] == 0
    assert finish["total_timeout"] is False
    assert SOURCE not in caplog.text
    assert "private-title" not in caplog.text
    assert "02-0000-0000" not in caplog.text


def test_document_timeout_logs_its_scope_and_preserves_success(caplog):
    async def scenario():
        cancelled = asyncio.Event()

        async def extract(context):
            if json.loads(context.payload)["title"] == "slow":
                try:
                    await asyncio.Event().wait()
                finally:
                    cancelled.set()
            return output()

        result = await enrichment.prepare_report_briefs(
            multiple_request("fast", "slow"),
            runner=SimpleNamespace(extract=extract),
            settings=replace(
                ReportBriefSettings(), timeout_seconds=0.03, total_timeout_seconds=2,
            ),
        )
        assert result[2].note is None
        assert result[3].note is not None
        assert cancelled.is_set()

    asyncio.run(scenario())
    results = {row["version_id"]: row for row in events(caplog, "report_brief_result")}
    assert results[2]["outcome"] == "model_success"
    assert results[3]["outcome"] == "fallback"
    assert results[3]["reason"] == "document_timeout"
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["model_success_count"] == finish["fallback_count"] == 1
    assert finish["total_timeout"] is False


def test_total_timeout_distinguishes_queued_call_from_active_model_wait(caplog):
    async def scenario():
        calls = 0

        async def extract(context):
            nonlocal calls
            calls += 1
            await asyncio.Event().wait()

        result = await enrichment.prepare_report_briefs(
            multiple_request("active", "queued"),
            runner=SimpleNamespace(extract=extract),
            settings=replace(
                ReportBriefSettings(), concurrency=1, timeout_seconds=2,
                total_timeout_seconds=0.05,
            ),
        )
        assert calls == 1
        assert all(brief.note is not None for brief in result.values())

    asyncio.run(scenario())
    finished_calls = events(caplog, "model_call_finish")
    assert len(finished_calls) == 2
    assert {row["stage"] for row in finished_calls} == {"queued", "model_wait"}
    assert all(row["outcome"] == "cancelled" for row in finished_calls)
    results = events(caplog, "report_brief_result")
    assert len(results) == 2
    assert all(row["reason"] == "total_timeout" for row in results)
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["fallback_count"] == 2
    assert finish["model_success_count"] == 0
    assert finish["total_timeout"] is True


def test_cache_hit_is_logged_without_another_model_call(caplog):
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    run(runner=runner)
    caplog.clear()

    result = run(runner=runner)

    assert result[2].note is None
    assert runner.extract.await_count == 1
    assert not events(caplog, "model_call_start")
    item, = events(caplog, "report_brief_result")
    assert item["outcome"] == "cache_hit"
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["cache_hit_count"] == 1
    assert finish["model_success_count"] == finish["fallback_count"] == 0


def test_identical_documents_log_one_model_call_and_one_shared_result(caplog):
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    result = run(multiple_request("same", "same"), runner)

    assert len(result) == 2
    assert runner.extract.await_count == 1
    assert len(events(caplog, "model_call_start")) == 1
    assert {row["outcome"] for row in events(caplog, "report_brief_result")} == {
        "model_success", "shared_call",
    }
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["model_success_count"] == finish["shared_call_count"] == 1
    assert finish["fallback_count"] == 0


def test_verified_facts_are_logged_as_reused_without_model_setup(caplog, monkeypatch):
    monkeypatch.setattr(enrichment, "SmallModelBriefRunner", lambda *_: pytest.fail("model setup"))

    result = run(verified_request(saved=True), settings=ReportBriefSettings(api_key=""))

    assert result[2].note is None
    assert result[2].uses_saved_checklist
    assert not events(caplog, "model_call_start")
    item, = events(caplog, "report_brief_result")
    assert item["outcome"] == "reused_facts"
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["reused_count"] == 1
    assert finish["model_success_count"] == finish["fallback_count"] == 0


def test_model_error_logs_only_error_type_and_remains_retryable(caplog):
    secret_error = "private-token-and-source-text-must-never-appear"
    runner = SimpleNamespace(
        extract=AsyncMock(side_effect=[RuntimeError(secret_error), output()]),
    )

    result = run(runner=runner)

    assert result[2].note is not None
    item, = events(caplog, "report_brief_result")
    assert item["outcome"] == "fallback"
    assert "RuntimeError" in caplog.text
    assert secret_error not in caplog.text
    assert SOURCE not in caplog.text
    assert run(runner=runner)[2].note is None
    assert runner.extract.await_count == 2


def test_validation_failure_is_distinct_from_model_failure_and_redacts_error(caplog, monkeypatch):
    sensitive_error = "sensitive raw text"
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))

    def reject(*args, **kwargs):
        raise ValueError(sensitive_error)

    monkeypatch.setattr(enrichment, "validate_brief", reject)

    result = run(runner=runner)

    runner.extract.assert_awaited_once()
    assert result[2].note is not None
    call, = events(caplog, "model_call_finish")
    assert call["outcome"] == "success"
    validation, = events(caplog, "report_brief_validation")
    assert validation["outcome"] == "error"
    assert validation["version_id"] == 2
    item, = events(caplog, "report_brief_result")
    assert item["outcome"] == "fallback"
    assert item["reason"] == "validation_error"
    assert "ValueError" in caplog.text
    assert sensitive_error not in caplog.text
    assert SOURCE not in caplog.text
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["fallback_count"] == 1
    assert finish["model_success_count"] == 0
