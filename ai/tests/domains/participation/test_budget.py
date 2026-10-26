import asyncio
import json
import logging

import httpx
import pytest
from langchain_core.messages import HumanMessage, SystemMessage

from app.core.openai_models import ChatOpenAI
from app.domains.participation import budget, service
from app.domains.participation.budget import BudgetExceeded, CallBudget, select_sources
from app.domains.participation.schemas import (
    Criterion,
    Draft,
    Review,
    RewriteRequest,
    Source,
    Statement,
)


def request():
    return RewriteRequest(
        criteria=[Criterion(code="COMPANY_SIZE", label="기업 규모", category="기업 기본 요건")],
        sources=[Source(id="s1", excerpt="신청기업은 중소기업이어야 합니다.")],
    )


def draft():
    return Draft(
        statements=[Statement(
            source_ids=["s1"], criterion="COMPANY_SIZE",
            text="신청기업은 중소기업이어야 합니다.", group_label="",
            supporting_quotes=["신청기업은 중소기업이어야 합니다."], kind="ELIGIBILITY",
        )], unrecoverable_ids=[],
    )


class Fake:
    def __init__(self, result=None):
        self.calls = []
        self.result = result or draft()

    def with_structured_output(self, schema, **kwargs):
        owner = self

        class Run:
            async def ainvoke(self, messages, **kwargs):
                owner.calls.append(schema)
                if schema is Draft:
                    return owner.result
                return Review(
                    verdicts=[dict(index=0, approved=True, errors=[])], uncovered_source_ids=[]
                )

        return Run()


def test_budget_includes_system_prompt_and_strict_schema_and_caps_total(monkeypatch):
    messages = [SystemMessage(content="instructions"), HumanMessage(content="small")]
    guard = CallBudget()
    size = guard.reserve(messages, Draft)
    assert size > sum(len(m.content) for m in messages)
    monkeypatch.setattr(budget, "MAX_DOCUMENT_INPUT_BYTES", size * 2 - 1)
    with pytest.raises(BudgetExceeded):
        guard.reserve(messages, Draft)
    assert guard.calls == 1


def test_two_requests_are_reserved_even_when_caller_does_not_get_a_response():
    guard = CallBudget()
    messages = [HumanMessage(content="small")]
    guard.reserve(messages, Draft)
    guard.reserve(messages, Review)
    with pytest.raises(BudgetExceeded):
        guard.reserve(messages, Draft)
    assert guard.calls == 2


def test_generation_payload_budget_stops_before_provider_call():
    req = request()
    req.criteria[0].category = "private-" * 4000
    model = Fake()
    result = asyncio.run(service.rewrite(req, model))
    assert not model.calls
    assert result.status == "UNAVAILABLE"
    assert result.evidence_only_ids == ["s1"]


def test_review_budget_exhaustion_hides_unverified_draft_without_retry():
    model = Fake(draft().model_copy(update={
        "statements": [draft().statements[0].model_copy(update={"text": "큰 답변 " * 8000})]
    }))
    result = asyncio.run(service.rewrite(request(), model))
    assert model.calls == [Draft]
    assert not result.statements
    assert result.status == "UNAVAILABLE"
    assert result.evidence_only_ids == ["s1"]


def test_selection_preserves_whole_table_and_prioritizes_applicant_conditions(monkeypatch):
    req = request()
    raw_table = "[표]\n지원유형\t국비\t자부담\n일반\t\t270만원 이상\n[/표]"
    req.sources = [
        Source(id="noise", excerpt="문의처 안내 " * 50),
        Source(id="eligibility", excerpt="신청 자격: 중소기업"),
        Source(id="table", excerpt=raw_table),
    ]
    monkeypatch.setattr(budget, "MAX_SELECTED_SOURCES", 1)
    assert [s.id for s in select_sources(req).sources] == ["eligibility"]
    monkeypatch.setattr(budget, "MAX_SELECTED_SOURCES", 12)
    monkeypatch.setattr(budget, "MAX_SELECTED_SOURCE_BYTES", len(raw_table.encode("utf-8")))
    req.sources = [Source(id="table", excerpt=raw_table)]
    assert select_sources(req).sources[0].excerpt == raw_table
    monkeypatch.setattr(budget, "MAX_SELECTED_SOURCE_BYTES", len(raw_table.encode("utf-8")) - 1)
    assert not select_sources(req).sources


def test_model_cannot_mark_an_unselected_source_irrelevant(monkeypatch):
    monkeypatch.setattr(budget, "MAX_SELECTED_SOURCES", 1)
    req = request()
    req.sources.append(Source(id="omitted", excerpt="추가 근거"))
    output = draft().model_copy(update={"irrelevant_source_ids": ["omitted"]})
    result = asyncio.run(service.rewrite(req, Fake(output)))
    assert result.evidence_only_ids == ["omitted"]
    assert not result.irrelevant_source_ids


def test_sdk_requests_use_cheap_model_strict_output_caps_and_safe_usage_logs(monkeypatch, caplog):
    monkeypatch.setenv("OPENAI_API_KEY", "test-not-a-real-key")
    monkeypatch.delenv("PARTICIPATION_REWRITE_MODEL", raising=False)
    monkeypatch.delenv("PARTICIPATION_REWRITE_REASONING_EFFORT", raising=False)
    monkeypatch.setattr("app.domains.participation.config.load_dotenv", lambda *_: None)
    payloads = []
    options = []

    async def scenario():
        def respond(req):
            payload = json.loads(req.content)
            payloads.append(payload)
            output = draft() if len(payloads) == 1 else Review(
                verdicts=[dict(index=0, approved=True, errors=[])], uncovered_source_ids=[]
            )
            return httpx.Response(200, json={
                "id": "chatcmpl-cost-test", "object": "chat.completion", "created": 0,
                "model": "gpt-5-nano", "choices": [{
                    "index": 0, "finish_reason": "stop",
                    "message": {"role": "assistant", "content": output.model_dump_json()},
                }],
                "usage": {
                    "prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150,
                    "completion_tokens_details": {"reasoning_tokens": 5},
                },
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            def factory(**kwargs):
                options.append(kwargs)
                return ChatOpenAI(**kwargs, http_async_client=client)
            monkeypatch.setattr(service, "ChatOpenAI", factory)
            return await service.rewrite(request())

    with caplog.at_level(logging.INFO, logger=service.__name__):
        result = asyncio.run(scenario())
    assert result.statements == draft().statements
    assert len(payloads) == 2
    assert all(p["model"] == "gpt-5-nano" and p["reasoning_effort"] == "minimal" for p in payloads)
    assert [p["max_completion_tokens"] for p in payloads] == [2048, 1024]
    assert all(o["max_retries"] == 0 for o in options)
    assert '"input_tokens": 120' in caplog.text and '"output_tokens": 30' in caplog.text
    assert "test-not-a-real-key" not in caplog.text
    assert request().sources[0].excerpt not in caplog.text
    data = json.loads(payloads[0]["messages"][-1]["content"])
    assert "raw_excerpt" not in data["rows"][0] and "text" not in data["rows"][0]


def test_conflicting_irrelevant_and_unrecoverable_source_stays_as_evidence():
    output = Draft(
        statements=[], unrecoverable_ids=["s1"], irrelevant_source_ids=["s1"]
    )
    model = Fake(output)
    result = asyncio.run(service.rewrite(request(), model))
    assert model.calls == [Draft, Review]
    assert result.evidence_only_ids == ["s1"]
    assert not result.irrelevant_source_ids
    assert not result.statements
