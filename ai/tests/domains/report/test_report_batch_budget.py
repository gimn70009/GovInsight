"""Batch deadlines account for unique model work while remaining bounded."""

import asyncio
import json
import logging
from collections import defaultdict
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.report import enrichment
from app.domains.report.config import ReportBriefSettings
from tests.domains.report.test_report_brief import output, request, run
from tests.domains.report.test_report_diagnostics import events, multiple_request
from tests.domains.report.test_report_fact_reuse import request as verified_request
from tests.domains.report.test_report_template import with_document


@pytest.fixture(autouse=True)
def isolated_budget(caplog, monkeypatch):
    enrichment._CACHE.clear()
    caplog.set_level(logging.INFO, logger=enrichment.logger.name)
    monkeypatch.setattr(enrichment.logger, "propagate", False)
    enrichment.logger.addHandler(caplog.handler)
    yield
    enrichment.logger.removeHandler(caplog.handler)
    enrichment._CACHE.clear()


def test_nine_distinct_calls_finish_in_three_waves_beyond_base_budget(caplog):
    async def scenario():
        active = peak = calls = 0

        async def extract(context):
            nonlocal active, peak, calls
            active += 1
            calls += 1
            peak = max(peak, active)
            try:
                await asyncio.sleep(0.04)
                return output()
            finally:
                active -= 1

        settings = replace(
            ReportBriefSettings(), concurrency=3, timeout_seconds=0.3,
            total_timeout_seconds=0.06, max_total_timeout_seconds=1,
        )
        started = asyncio.get_running_loop().time()
        result = await enrichment.prepare_report_briefs(
            multiple_request(*(f"notice-{index}" for index in range(9))),
            settings=settings, runner=SimpleNamespace(extract=extract),
        )
        assert asyncio.get_running_loop().time() - started > settings.total_timeout_seconds
        assert calls == 9 and peak == 3 and active == 0
        assert len(result) == 9 and all(brief.note is None for brief in result.values())

    asyncio.run(scenario())
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["required_model_call_count"] == finish["model_success_count"] == 9
    assert finish["total_timeout_seconds"] == pytest.approx(0.9)
    assert finish["total_timeout"] is False
    assert finish["fallback_count"] == 0


def test_only_uncached_unique_model_work_expands_budget(caplog):
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    run(multiple_request("cached"), runner)
    caplog.clear()
    req = multiple_request("cached", "fresh", "fresh")
    verified = verified_request(saved=True).documents[0].model_copy(update={"version_id": 5})
    empty = with_document().documents[0].model_copy(update={"version_id": 6})
    req = req.model_copy(update={"documents": [*req.documents, verified, empty]})
    settings = replace(
        ReportBriefSettings(), concurrency=1, timeout_seconds=0.2,
        total_timeout_seconds=0.05, max_total_timeout_seconds=1,
    )

    result = run(req, runner, settings)

    assert runner.extract.await_count == 2  # One warmup and one new input.
    assert result[6].note is not None
    assert all(result[index].note is None for index in range(2, 6))
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["document_count"] == 5
    assert finish["required_model_call_count"] == 1
    assert finish["total_timeout_seconds"] == pytest.approx(0.2)
    assert finish["model_success_count"] == finish["cache_hit_count"] == 1
    assert finish["shared_call_count"] == finish["reused_count"] == 1
    assert finish["fallback_count"] == 1
    budget, = events(caplog, "report_brief_budget")
    assert budget["required_model_call_count"] == 1
    assert budget["total_timeout_seconds"] == pytest.approx(0.2)


def test_missing_api_key_keeps_base_budget_without_model_setup(caplog, monkeypatch):
    monkeypatch.setattr(enrichment, "SmallModelBriefRunner", lambda *_: pytest.fail("model setup"))
    settings = replace(
        ReportBriefSettings(), api_key="", timeout_seconds=0.2,
        total_timeout_seconds=0.05, max_total_timeout_seconds=1,
    )

    result = run(request(), settings=settings)

    assert result[2].note is not None
    assert not events(caplog, "model_call_start")
    assert not events(caplog, "report_brief_budget")
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["required_model_call_count"] == 0
    assert finish["total_timeout_seconds"] == settings.total_timeout_seconds


def test_expired_cache_entry_is_counted_as_required_model_work(caplog):
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    run(runner=runner)
    for key, (saved_at, value) in list(enrichment._CACHE.items()):
        enrichment._CACHE[key] = (saved_at - 100, value)
    caplog.clear()
    settings = replace(
        ReportBriefSettings(), timeout_seconds=0.2, cache_ttl_seconds=1,
        total_timeout_seconds=0.05, max_total_timeout_seconds=1,
    )

    assert run(runner=runner, settings=settings)[2].note is None

    assert runner.extract.await_count == 2
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["required_model_call_count"] == 1
    assert finish["cache_hit_count"] == 0
    assert finish["total_timeout_seconds"] == pytest.approx(0.2)


def test_hard_cap_preserves_completed_result_and_cancels_active_and_queued(caplog):
    async def scenario():
        started = []
        cancelled = asyncio.Event()

        async def extract(context):
            title = json.loads(context.payload)["title"]
            started.append(title)
            if title == "fast":
                return output()
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()

        settings = replace(
            ReportBriefSettings(), concurrency=1, timeout_seconds=1,
            total_timeout_seconds=0.03, max_total_timeout_seconds=0.15,
        )
        result = await enrichment.prepare_report_briefs(
            multiple_request("fast", "active", "queued"), settings=settings,
            runner=SimpleNamespace(extract=extract),
        )
        assert started == ["fast", "active"]
        assert cancelled.is_set()
        assert result[2].note is None
        assert result[3].note is not None and result[4].note is not None

    asyncio.run(scenario())
    cancelled_calls = [
        row for row in events(caplog, "model_call_finish") if row["outcome"] == "cancelled"
    ]
    assert {row["stage"] for row in cancelled_calls} == {"queued", "model_wait"}
    finish, = events(caplog, "report_brief_batch_finish")
    assert finish["total_timeout"] is True
    assert finish["total_timeout_seconds"] == pytest.approx(0.15)
    assert finish["required_model_call_count"] == 3
    assert finish["model_success_count"] == 1 and finish["fallback_count"] == 2


def test_budget_reschedule_uses_original_start_instead_of_current_time(monkeypatch, caplog):
    schedules = defaultdict(list)
    real_reschedule = asyncio.Timeout.reschedule
    real_create_task = asyncio.create_task
    launches = 0

    def record_reschedule(timer, when):
        schedules[timer].append(when)
        return real_reschedule(timer, when)

    def stagger_model_jobs(coroutine, **kwargs):
        nonlocal launches
        if coroutine.cr_code.co_name != "extract":
            return real_create_task(coroutine, **kwargs)
        delay = launches * 0.04
        launches += 1

        async def delayed_start():
            await asyncio.sleep(delay)
            return await coroutine

        return real_create_task(delayed_start(), **kwargs)

    monkeypatch.setattr(asyncio.Timeout, "reschedule", record_reschedule)
    monkeypatch.setattr(enrichment.asyncio, "create_task", stagger_model_jobs)
    settings = replace(
        ReportBriefSettings(), concurrency=1, timeout_seconds=0.4,
        total_timeout_seconds=0.15, max_total_timeout_seconds=2,
    )
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))

    result = run(multiple_request("first", "second", "third"), runner, settings)

    assert all(brief.note is None for brief in result.values())
    deadlines, = [values for values in schedules.values() if len(values) > 1]
    budgets = [settings.total_timeout_seconds] + [
        row["total_timeout_seconds"] for row in events(caplog, "report_brief_budget")
    ]
    assert len(deadlines) == len(budgets) == 4
    anchors = [deadline - budget for deadline, budget in zip(deadlines, budgets)]
    assert max(anchors) - min(anchors) < 0.001


def test_external_cancellation_propagates_and_cleans_up_inflight_call(caplog):
    async def scenario():
        started = asyncio.Event()
        cancelled = asyncio.Event()

        async def extract(context):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()

        settings = replace(
            ReportBriefSettings(), concurrency=1, timeout_seconds=1,
            total_timeout_seconds=0.1, max_total_timeout_seconds=2,
        )
        task = asyncio.create_task(enrichment.prepare_report_briefs(
            multiple_request("active", "queued"), settings=settings,
            runner=SimpleNamespace(extract=extract),
        ))
        await asyncio.wait_for(started.wait(), timeout=1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert cancelled.is_set()

    asyncio.run(scenario())
    finished = events(caplog, "model_call_finish")
    assert len(finished) == 2 and all(row["outcome"] == "cancelled" for row in finished)
    assert not events(caplog, "report_brief_batch_finish")
