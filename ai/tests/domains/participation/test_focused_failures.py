"""Provider delays and failure provenance without external calls or persisted data."""

import asyncio
from types import SimpleNamespace

import httpx
import pytest

from app.domains.participation import focused, service
from app.domains.participation.schemas import Criterion, RewriteRequest, Source


def request():
    return RewriteRequest(
        criteria=[Criterion(code=c, label=c, category="신청 자격") for c in focused.CODES],
        sources=[Source(id="a", excerpt="창업 후 7년 이내인 기업이 신청할 수 있습니다.",
                        group_label="신청자격")],
    )


class Model:
    def __init__(self, *, delay=0, error=None, invalid=False):
        self.delay, self.error, self.invalid = delay, error, invalid
        self.calls = 0

    def with_structured_output(self, *args, **kwargs):
        return self

    async def ainvoke(self, *args, **kwargs):
        self.calls += 1
        await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        if self.invalid:
            return {"items": "private invalid output"}
        return focused.Extraction(items=[focused.ExtractedCondition(
            source_id="a", criterion="BUSINESS_AGE", kind="ELIGIBILITY", quote="L1",
            text=request().sources[0].excerpt,
        )], unresolved_source_ids=[])


def test_same_delayed_response_survives_new_call_and_document_deadlines(monkeypatch):
    # Scale seconds by 100 so the 65-second response exercises both old ceilings quickly.
    real_timeout = asyncio.timeout
    monkeypatch.setattr(focused, "asyncio", SimpleNamespace(
        timeout=lambda seconds: real_timeout(seconds / 100),
    ))
    current = (focused.TIMEOUT_SECONDS, focused.DOCUMENT_TIMEOUT_SECONDS)
    assert current == (120, 150)
    monkeypatch.setattr(focused, "TIMEOUT_SECONDS", 45)
    monkeypatch.setattr(focused, "DOCUMENT_TIMEOUT_SECONDS", 60)
    old_model = Model(delay=0.65)
    previous = asyncio.run(service.rewrite(request(), old_model))
    assert previous.status == "UNAVAILABLE"
    assert previous.diagnostics == {"MODEL_TIMEOUT": 1}

    monkeypatch.setattr(focused, "TIMEOUT_SECONDS", current[0])
    monkeypatch.setattr(focused, "DOCUMENT_TIMEOUT_SECONDS", current[1])
    new_model = Model(delay=0.65)
    current_result = asyncio.run(service.rewrite(request(), new_model))
    assert current_result.status == "READY"
    assert current_result.statements[0].text == request().sources[0].excerpt
    assert old_model.calls == new_model.calls == 1


def test_document_deadline_includes_queue_without_issuing_a_model_call(monkeypatch):
    async def scenario():
        monkeypatch.setattr(focused, "_semaphore", asyncio.Semaphore(0))
        monkeypatch.setattr(focused, "DOCUMENT_TIMEOUT_SECONDS", 0.01)
        model = Model()
        result = await service.rewrite(request(), model)
        assert model.calls == 0
        return result

    result = asyncio.run(scenario())
    assert result.status == "UNAVAILABLE"
    assert result.diagnostics == {"DOCUMENT_TIMEOUT": 1}
    assert result.evidence_only_ids == ["a"]


@pytest.mark.parametrize("error,invalid,reason", [
    (httpx.ReadTimeout("private provider body"), False, "MODEL_TIMEOUT"),
    (RuntimeError("private provider body"), False, "MODEL_ERROR"),
    (None, True, "RESPONSE_INVALID"),
])
def test_failure_reason_is_persistable_without_private_error_text(error, invalid, reason, caplog):
    model = Model(error=error, invalid=invalid)
    result = asyncio.run(service.rewrite(request(), model))
    assert model.calls == 1
    assert result.status == "UNAVAILABLE"
    assert result.diagnostics == {reason: 1}
    assert not result.statements
    assert result.evidence_only_ids == ["a"]
    assert "private" not in result.model_dump_json()
    assert "private" not in caplog.text


def test_configuration_and_input_budget_failures_are_distinct_and_make_no_call(monkeypatch):
    monkeypatch.setattr(focused.RewriteSettings, "from_env", lambda: SimpleNamespace(api_key=""))
    result = asyncio.run(service.rewrite(request()))
    assert result.diagnostics == {"MODEL_NOT_CONFIGURED": 1}

    req = request()
    req.sources[0].group_label = "신청자격" * 8000
    model = Model()
    result = asyncio.run(service.rewrite(req, model))
    assert result.diagnostics == {"INPUT_BUDGET_EXCEEDED": 1}
    assert model.calls == 0
