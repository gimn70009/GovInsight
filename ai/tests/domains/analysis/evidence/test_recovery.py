import asyncio
import json
from dataclasses import replace

import httpx
import pytest
from langchain_core.messages import AIMessage

from app.core.openai_models import ChatOpenAI
from app.domains.analysis.evidence.agent import AnalysisEvidenceAgent
from app.domains.analysis.evidence.coverage import confirmed_ranges, unread_gaps
from app.domains.analysis.evidence.recovery import EvidenceRecoveryMiddleware
from app.domains.analysis.evidence.selection import range_covered
from app.domains.analysis.evidence.targeted import search_sources
from app.domains.analysis.evidence.tools import (
    AnalysisToolContext,
    read_attachment_texts,
    read_document_content,
)
from scripts.evaluate_targeted_evidence import cases, context_for
from tests.domains.analysis.evidence.fakes import ScriptedEvidenceModel, calls
from tests.domains.analysis.evidence.test_targeted import attachment, call, notice, omitted_notice


def seed_base(context):
    context.result_cache.update(
        get_document_content=read_document_content(context),
        get_attachment_texts=read_attachment_texts(context),
        get_company_profile="{}",
    )
    return context


def report(sections):
    section = next(value for value in sections if value.startswith("<evidence_check>"))
    return json.loads(section[len("<evidence_check>"):section.rindex("</")])


@pytest.mark.parametrize(("ranges", "expected"), [
    ([], False), ([(0, 5)], False), ([(0, 5), (5, 10)], True),
    ([(0, 4), (5, 10)], False), ([(0, 20)], True), ([(4, 10)], False),
])
def test_range_coverage_requires_whole_unit(ranges, expected):
    assert range_covered(2, 10, ranges) is expected


@pytest.mark.parametrize("case", cases(), ids=lambda case: case.name)
@pytest.mark.parametrize("budget", [2500, 40_000])
def test_keyword_gap_check_matches_controlled_omissions(case, budget):
    context = seed_base(replace(context_for(case), max_text_chars=budget))
    gaps = unread_gaps(case.document, confirmed_ranges(case.document, context.result_cache))
    assert bool(gaps) == case.needs_extra
    if case.name == "long_hidden_exclusion":
        assert gaps[0].topic == "exclusion"
    if "documents" in case.name:
        assert gaps[0].topic == "documents"


def test_long_source_with_covered_markers_does_not_force_search():
    doc = notice("신청 자격: 중소기업\n\n" + "일반 안내입니다.\n\n" * 100)
    context = seed_base(AnalysisToolContext(doc, 300))
    guard = EvidenceRecoveryMiddleware(["get_document_content", "get_company_profile"], 8)
    assert guard.pending(context) == []
    assert guard.report(context) is None


def test_identical_sources_share_coverage_and_do_not_create_false_gaps():
    doc = notice("본문", [
        attachment(4, "제출서류: 신청서"), attachment(5, "제출서류: 신청서"),
    ])
    context = seed_base(AnalysisToolContext(doc, 100))
    ranges = confirmed_ranges(doc, context.result_cache)
    assert ranges["attachment:4"] == ranges["attachment:5"]
    assert unread_gaps(doc, ranges) == []


def test_ambiguous_attachment_ids_cannot_establish_coverage():
    doc = notice("본문", [
        attachment(4, "제출서류: 신청서"), attachment(4, "신청 제한: 휴업 기업"),
    ])
    context = seed_base(AnalysisToolContext(doc, 100))
    ranges = confirmed_ranges(doc, context.result_cache)
    assert ranges["attachment:4"] == []
    assert len(unread_gaps(doc, ranges)) == 2


def test_unread_search_skips_covered_units_and_pagination_stays_stable():
    text = "\n\n".join(f"제출서류: 확인서 {index}" for index in range(8))
    doc = notice(text)
    context = seed_base(AnalysisToolContext(doc, 60))
    ranges = confirmed_ranges(doc, context.result_cache)
    first = search_sources(doc, ["제출서류"], "body", 0, 1500, ranges)
    assert all(not range_covered(
        hit["suggestedRange"]["start"], hit["suggestedRange"]["end"], ranges["body"],
    ) for hit in first["matches"])
    assert first["alreadyReadRangesExcluded"] is True
    assert first["nextOffset"] is not None
    second = search_sources(doc, ["제출서류"], "body", first["nextOffset"], 1500, ranges)
    assert first["matches"][-1]["matchStart"] < second["matches"][0]["matchStart"]


def test_empty_search_hit_or_query_string_never_counts_as_confirmed_evidence():
    doc, _ = omitted_notice()
    context = seed_base(AnalysisToolContext(doc, 300))
    initial = confirmed_ranges(doc, context.result_cache)
    fake = json.dumps(dict(
        status="OK", queries=[doc.content_text],
        matches=[dict(sourceId="body", text="", coverage=dict(
            selectedChars=len(doc.content_text),
            sourceRanges=[dict(start=0, end=len(doc.content_text))],
        ))],
    ))
    assert confirmed_ranges(doc, context.result_cache, [fake]) == initial


def test_model_ignoring_forced_choice_is_flagged_without_hidden_retries():
    doc, _ = omitted_notice()
    model = ScriptedEvidenceModel(steps=[
        calls("get_document_content", "get_company_profile"), AIMessage(content="완료"),
    ])
    context = AnalysisToolContext(doc, 300)
    sections, used = asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    check = report(sections)
    assert check["status"] == "CHECK_REQUIRED"
    assert "exclusion" in check["unresolved_topics"]
    assert check["forced_calls"] == 1
    assert len(model.seen) == 2
    assert "search_document_evidence" not in used


def test_no_match_is_not_retried_or_treated_as_conditions_absent():
    doc, _ = omitted_notice()
    model = ScriptedEvidenceModel(steps=[
        calls("get_document_content", "get_company_profile"),
        call("search_document_evidence", {"queries": ["존재하지않는표현"]}),
        AIMessage(content="완료"),
    ])
    sections, _ = asyncio.run(
        AnalysisEvidenceAgent(model).collect(AnalysisToolContext(doc, 300)),
    )
    assert report(sections)["status"] == "CHECK_REQUIRED"
    assert len(model.seen) == 3


def completion(content="", tool_calls=()):
    return dict(
        id="test", object="chat.completion", created=0, model="gpt-5-mini",
        choices=[dict(index=0, message=dict(
            role="assistant", content=content, tool_calls=list(tool_calls),
        ), finish_reason="tool_calls" if tool_calls else "stop")],
        usage=dict(prompt_tokens=10, completion_tokens=5, total_tokens=15),
    )


def wire_call(name, arguments, identity):
    return dict(id=identity, type="function", function=dict(
        name=name, arguments=json.dumps(arguments, ensure_ascii=False),
    ))


@pytest.mark.parametrize("case", cases(), ids=lambda case: case.name)
@pytest.mark.parametrize("broad_queries", [False, True])
def test_real_adapter_enforces_choice_and_passes_recovered_source(case, broad_queries):
    payloads = []

    async def http_handler(request):
        payload = json.loads(request.content)
        payloads.append(payload)
        choice = payload.get("tool_choice")
        tool_messages = [m for m in payload["messages"] if m["role"] == "tool"]
        if not tool_messages:
            names = ["get_document_content", "get_company_profile"]
            if case.document.attachments:
                names.append("get_attachment_texts")
            response = completion(tool_calls=[
                wire_call(name, {}, f"base-{i}") for i, name in enumerate(names)
            ])
        elif isinstance(choice, dict):
            assert payload["parallel_tool_calls"] is False
            assert len(payload["tools"]) == 1
            definition = payload["tools"][0]["function"]
            assert definition["strict"] is True
            parameters = definition["parameters"]
            assert parameters["additionalProperties"] is False
            assert set(parameters["required"]) == set(parameters["properties"])
            assert "runtime" not in parameters["properties"]
            name = choice["function"]["name"]
            if name == "search_document_evidence":
                guidance = json.loads(payload["messages"][0]["content"].rsplit("\n", 1)[1])
                queries = list(dict.fromkeys(hint["query"] for hint in guidance["candidates"]))
                assert parameters["properties"]["queries"]["items"]["enum"] == queries
                arguments = dict(
                    queries=["지원", "제출", "신청"] if broad_queries else queries,
                    # Candidate filtering must work even if the model omits unread_only.
                    unread_only=not broad_queries,
                )
            else:
                search = next(
                    json.loads(item["content"]) for item in reversed(tool_messages)
                    if "matches" in json.loads(item["content"])
                )
                hit = next(item for item in search["matches"]
                           if item["contextStatus"] == "READ_REQUIRED")
                arguments = dict(source_id=hit["sourceId"],
                                 start=hit["matchStart"], end=hit["matchEnd"])
                assert parameters["properties"]["start"]["enum"] == [hit["matchStart"]]
            response = completion(tool_calls=[wire_call(name, arguments, f"call-{len(payloads)}")])
        else:
            response = completion("완료")
        return httpx.Response(200, json=response)

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(http_handler)) as client:
            model = ChatOpenAI(
                model="gpt-5-mini", api_key="local-test-placeholder", http_async_client=client,
            )
            return await AnalysisEvidenceAgent(model).collect(context_for(case))

    sections, used = asyncio.run(run())
    returned = "\n".join(sections)
    assert all(marker in returned for marker in case.expected)
    assert ("search_document_evidence" in used) == case.needs_extra
    assert ("read_document_evidence" in used) == case.needs_read
    assert len(payloads) == 2 + case.needs_read
    if case.needs_extra:
        assert report(sections)["status"] == "CANDIDATES_RECOVERED"
        assert isinstance(payloads[-1]["tool_choice"], dict)
    else:
        assert not any(value.startswith("<evidence_check>") for value in sections)
        assert all("tool_choice" not in payload for payload in payloads)


def test_unmatched_query_can_read_verified_local_candidate_without_another_search():
    case = cases()[1]
    seeded = seed_base(context_for(case))
    gap = unread_gaps(
        case.document, confirmed_ranges(case.document, seeded.result_cache),
    )[0]
    model = ScriptedEvidenceModel(steps=[
        calls("get_document_content", "get_company_profile", "get_attachment_texts"),
        call("search_document_evidence", {"queries": ["없는표현"]}, "search"),
        call("read_document_evidence", {
            "source_id": gap.source_id, "start": gap.start, "end": gap.end,
        }, "read"),
    ])
    sections, used = asyncio.run(AnalysisEvidenceAgent(model).collect(context_for(case)))
    assert report(sections)["status"] == "CANDIDATES_RECOVERED"
    assert all(marker in "\n".join(sections) for marker in case.expected)
    assert used.count("search_document_evidence") == 1
    assert used.count("read_document_evidence") == 1
    assert len(model.seen) == 3


def test_oversized_context_keeps_check_required_within_two_requests():
    critical = "신청 제한: " + "충족 여부 " * 600 + "인 기업은 신청할 수 없습니다."
    doc = notice("일반 안내\n\n" * 100 + critical)
    start = doc.content_text.index("신청 제한")
    model = ScriptedEvidenceModel(steps=[
        calls("get_document_content", "get_company_profile"),
        call("search_document_evidence", {"queries": ["신청 제한"]}, "search"),
        call("read_document_evidence", {
            "source_id": "body", "start": start, "end": start + 5,
        }, "read"),
        AIMessage(content="완료"),
    ])
    context = AnalysisToolContext(doc, 1000)
    sections, _ = asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    assert report(sections)["status"] == "CHECK_REQUIRED"
    assert context.targeted.selected_chars == 0
    assert len(context.targeted.cache) == 2
    assert len(model.seen) == 3
