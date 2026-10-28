import asyncio
import json
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from langchain_core.messages import AIMessage

from app.domains.analysis.evidence.agent import AnalysisEvidenceAgent, EvidenceCollectionError
from app.domains.analysis.evidence.targeted import (
    SEARCH_TOOLS,
    TargetedEvidence,
    read_source,
    search_sources,
)
from app.domains.analysis.evidence.tools import (
    AnalysisToolContext,
    optional_evidence_tools,
    read_document_content,
)
from app.domains.analysis.schemas.request import AnalysisAttachmentRequest
from app.domains.analysis.workflow.agent import LangChainAnalysisRunner
from tests.domains.analysis.evidence.fakes import ScriptedEvidenceModel, calls, tool_results
from tests.domains.analysis.evidence.test_agent import document


def notice(text, attachments=()):
    return document(attachments=False).model_copy(
        update={"content_text": text, "attachments": list(attachments)}
    )


def attachment(identity, text, name="동일이름.pdf"):
    return AnalysisAttachmentRequest(
        attachment_id=identity, file_name=name, extracted_text=text,
    )


def call(name, arguments, identity="targeted-call"):
    return AIMessage(content="", tool_calls=[{"name": name, "args": arguments, "id": identity}])


def omitted_notice():
    filler = "\n\n".join(
        f"지원 대상과 신청 자격, 제출 기한, 사업비와 과업, 문의 안내 {index}입니다."
        for index in range(35)
    )
    critical = "신청 제한: 공고일 현재 휴업 중인 기업은 신청할 수 없습니다."
    return notice(filler + "\n\n" + critical + "\n\n마지막 안내입니다."), critical


def test_search_recovers_omitted_condition_with_exact_source_offsets():
    doc, critical = omitted_notice()
    context = AnalysisToolContext(doc, 300)
    assert critical not in json.loads(read_document_content(context))["contentText"]
    result = search_sources(doc, ["신청 제한", "지원 제외"], "all", 0, 300)
    hit = result["matches"][0]
    assert result["status"] == "OK"
    assert critical in hit["text"]
    assert hit["sourceId"] == "body"
    assert result["versionId"] == doc.version_id
    span = hit["coverage"]["sourceRanges"][0]
    assert doc.content_text[span["start"]:span["end"]] == hit["text"]
    assert doc.content_text[hit["matchStart"]:hit["matchEnd"]] == "신청 제한"


def test_literal_search_handles_whitespace_and_regex_characters_without_normalizing_source():
    text = "😀 안내\r\n\r\n지원\t 제외: [특례](A+B)는 적용하지 않습니다."
    doc = notice(text)
    for query in ("지원 제외", "[특례](A+B)"):
        hit = search_sources(doc, [query], "body", 0, 1000)["matches"][0]
        assert hit["text"] == text[hit["suggestedRange"]["start"]:hit["suggestedRange"]["end"]]
    assert search_sources(doc, [".*"], "body", 0, 1000)["status"] == "NO_MATCH"


def test_same_filename_sources_are_separate_and_previous_analysis_is_not_searched():
    doc = notice("현재 본문", [
        attachment(4, "신청 제한: 휴업 기업은 제외합니다."),
        attachment(5, "신청 제한: 체납 기업은 제외합니다."),
    ]).model_copy(update={"previous_analysis": document(updated=True).previous_analysis})
    result = search_sources(doc, ["신청 제한"], "all", 0, 1500)
    assert [hit["sourceId"] for hit in result["matches"]] == ["attachment:4", "attachment:5"]
    targeted = search_sources(doc, ["신청 제한"], "attachment:5", 0, 1500)
    assert "체납" in targeted["matches"][0]["text"]
    assert search_sources(doc, ["이전 분석"], "all", 0, 1500)["status"] == "NO_MATCH"


def test_identical_attachments_do_not_repeat_hits_but_can_be_targeted_by_id():
    text = "지원 제외: 휴업 중인 기업"
    doc = notice("안내", [attachment(4, text), attachment(5, text)])
    assert len(search_sources(doc, ["지원 제외"], "all", 0, 1000)["matches"]) == 1
    assert search_sources(doc, ["지원 제외"], "attachment:5", 0, 1000)["matches"][0][
        "sourceId"
    ] == "attachment:5"


@pytest.mark.parametrize(
    ("source_id", "expected"),
    [("attachment:99", "UNKNOWN_SOURCE"), ("attachment:4", "SOURCE_UNAVAILABLE"),
     ("../../secret", "UNKNOWN_SOURCE")],
)
def test_missing_sources_do_not_fall_back_to_another_document(source_id, expected):
    doc = notice("지원 제외: 현재 본문", [attachment(4, None)])
    assert search_sources(doc, ["지원 제외"], source_id, 0, 1000)["status"] == expected
    assert read_source(doc, source_id, 0, 2, 1000)["status"] == expected


def test_duplicate_ids_are_reported_as_ambiguous():
    doc = notice("본문", [attachment(4, "조건 가"), attachment(4, "조건 나")])
    assert search_sources(doc, ["조건"], "all", 0, 1000)["status"] == "AMBIGUOUS_SOURCE"
    assert read_source(doc, "attachment:4", 0, 2, 1000)["status"] == "AMBIGUOUS_SOURCE"


def test_no_match_and_unavailable_files_are_explicit():
    doc = notice("일반 안내", [attachment(4, None)])
    result = search_sources(doc, ["지원 제외"], "all", 0, 1000)
    assert result["status"] == "NO_MATCH"
    assert result["unavailableSourceIds"] == ["attachment:4"]
    assert result["selectedChars"] == 0
    assert "조건 부재" in result["notice"]
    assert search_sources(doc, ["  "], "all", 0, 1000)["status"] == "INVALID_QUERY"


def test_search_has_bounded_hits_and_pagination():
    doc = notice("\n\n".join(f"지원 제외 조건 {index}입니다." for index in range(7)))
    first = search_sources(doc, ["지원 제외"], "body", 0, 1000)
    second = search_sources(doc, ["지원 제외"], "body", first["nextOffset"], 1000)
    assert len(first["matches"]) == 5
    assert first["nextOffset"] == 5
    assert len(second["matches"]) == 2
    assert second["nextOffset"] is None
    assert first["matches"][-1]["matchStart"] < second["matches"][0]["matchStart"]


def test_search_and_read_preserve_exception_and_surrounding_context():
    text = "공급기업 참여 안내\n\n지원대상: 중소기업\n\n다만, 휴업 기업은 제외합니다.\n\n접수 안내"
    doc = notice(text)
    start = text.index("지원대상")
    result = read_source(doc, "body", start, start + 4, 1000)
    assert result["status"] == "OK"
    assert "지원대상: 중소기업" in result["text"]
    assert "다만, 휴업 기업은 제외합니다." in result["text"]
    assert "공급기업 참여 안내" in result["text"]
    span = result["coverage"]["sourceRanges"][0]
    assert result["text"] == text[span["start"]:span["end"]]
    assert result["selectedChars"] == len(result["text"])


def test_long_search_hit_requests_read_and_read_returns_complete_unit():
    text = "지원 제외: " + "여러 조건을 함께 확인합니다. " * 60 + "휴업 기업은 신청할 수 없습니다."
    doc = notice("앞 안내\n\n" + text + "\n\n뒤 안내")
    hit = search_sources(doc, ["지원 제외"], "body", 0, 3000)["matches"][0]
    assert hit["contextStatus"] == "READ_REQUIRED"
    assert hit["text"] == ""
    result = read_source(doc, "body", hit["matchStart"], hit["matchEnd"], 3000)
    assert result["status"] == "OK"
    assert text in result["text"]
    assert len(result["text"]) <= 2000


def test_long_unsplittable_negative_condition_is_never_cut_into_an_affirmative():
    text = "신청 자격: " + "조건 " * 2000 + "인 기업은 신청할 수 없습니다."
    doc = notice(text)
    hit = search_sources(doc, ["신청 자격"], "body", 0, 3000)["matches"][0]
    assert not hit["text"]
    result = read_source(doc, "body", hit["matchStart"], hit["matchEnd"], 3000)
    assert result["status"] == "CONTEXT_TOO_LARGE"
    assert not result["text"]
    assert result["selectedChars"] == 0


@pytest.mark.parametrize(("start", "end"), [(-1, 2), (1, 1), (5, 2), (0, 100)])
def test_out_of_bounds_ranges_are_rejected(start, end):
    result = read_source(notice("조건입니다."), "body", start, end, 100)
    assert result["status"] == "INVALID_RANGE"
    assert result["text"] == ""


def test_exhausted_budget_does_not_create_source_evidence():
    doc = notice("지원 제외: 휴업 기업")
    result = search_sources(doc, ["지원 제외"], "body", 0, 0)
    assert result["matches"][0]["text"] == ""
    assert result["selectedChars"] == 0
    assert read_source(doc, "body", 0, 2, 0)["status"] == "BUDGET_EXHAUSTED"


def test_parallel_calls_share_budget_and_repeated_request_uses_cache():
    state = TargetedEvidence()

    def run(index):
        return state.run(
            "read_document_evidence", str(index), {"index": index}, 10_000,
            lambda remaining: {"selectedChars": min(2000, remaining)},
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(run, (0, 1)))
    assert state.selected_chars == 3000
    assert sum(json.loads(result)["selectedChars"] for result in results) == 3000
    assert run(0) == results[0]
    assert state.selected_chars == 3000
    assert json.loads(run(2))["status"] == "REQUEST_LIMIT"
    assert state.contains("0", "read_document_evidence", results[0])
    assert not state.contains("not-executed", "read_document_evidence", results[0])


def test_small_text_budget_is_also_the_additional_read_limit():
    state = TargetedEvidence()
    result = state.run("read_document_evidence", "one", {}, 12,
                       lambda remaining: {"selectedChars": remaining})
    assert json.loads(result)["selectedChars"] == 12


def test_short_documents_do_not_offer_extra_tools_or_extra_search_prompt():
    model = ScriptedEvidenceModel(steps=[
        calls("get_document_content", "get_company_profile"), AIMessage(content="완료"),
    ])
    context = AnalysisToolContext(document(attachments=False), 10_000)
    asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    assert not (SEARCH_TOOLS & set(model.available_tools))
    assert len(model.seen) == 2
    assert "search_document_evidence" not in model.seen[0][0].content
    duplicated = notice("본문", [attachment(4, "내용"), attachment(5, "내용")])
    assert not optional_evidence_tools(AnalysisToolContext(duplicated, 3))


def test_agent_passes_actual_search_result_to_analysis_without_internal_summary():
    doc, critical = omitted_notice()

    def decide(messages):
        results = tool_results(messages)
        if not results:
            return calls("get_document_content", "get_company_profile")
        if not any(result.name == "search_document_evidence" for result in results):
            return call("search_document_evidence", {"queries": ["신청 제한"]})
        return AIMessage(content="PRIVATE_SUMMARY")

    model = ScriptedEvidenceModel(steps=[decide])
    context = AnalysisToolContext(doc, 300)
    sections, used = asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    assert used == ["get_document_content", "get_company_profile", "search_document_evidence"]
    assert critical in "\n".join(sections)
    assert "<targeted_search>" in "\n".join(sections)
    assert "PRIVATE_SUMMARY" not in "\n".join(sections)
    assert len(model.seen) == 3  # One optional local search, no additional model type.


def test_agent_search_then_reads_exact_position():
    critical = "지원 제외: " + "여러 조건을 확인합니다. " * 65 + "휴업 기업은 신청할 수 없습니다."
    doc = notice(("일반 안내\n\n" * 400) + critical)

    def decide(messages):
        results = tool_results(messages)
        if not results:
            return calls("get_document_content", "get_company_profile")
        search = next((r for r in results if r.name == "search_document_evidence"), None)
        if search is None:
            return call("search_document_evidence", {"queries": ["지원 제외"]}, "search")
        if not any(r.name == "read_document_evidence" for r in results):
            hit = json.loads(search.content)["matches"][0]
            assert hit["contextStatus"] == "READ_REQUIRED"
            return call("read_document_evidence", {
                "source_id": hit["sourceId"], "start": hit["matchStart"], "end": hit["matchEnd"],
            }, "read")
        return AIMessage(content="완료")

    context = AnalysisToolContext(doc, 2000)
    sections, used = asyncio.run(
        AnalysisEvidenceAgent(ScriptedEvidenceModel(steps=[decide])).collect(context)
    )
    assert used[-2:] == ["search_document_evidence", "read_document_evidence"]
    assert critical in "\n".join(sections)
    assert context.targeted.selected_chars <= 2000


def test_different_queries_are_not_overwritten_and_repeats_are_not_forwarded_twice():
    doc = notice("안내입니다.\n\n" * 100 + "기한 조건: 월요일\n\n제외 조건: 휴업")
    model = ScriptedEvidenceModel(steps=[
        calls("get_document_content", "get_company_profile"),
        call("search_document_evidence", {"queries": ["기한 조건"]}, "first"),
        call("search_document_evidence", {"queries": ["제외 조건"]}, "second"),
        call("search_document_evidence", {"queries": ["기한 조건"]}, "repeat"),
        AIMessage(content="완료"),
    ])
    context = AnalysisToolContext(doc, 100)
    sections, used = asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    assert used.count("search_document_evidence") == 3
    assert sum(section.startswith("<targeted_search>") for section in sections) == 2
    assert len(context.targeted.cache) == 2


def test_optional_search_cannot_replace_required_attachment_lookup():
    doc = notice("안내\n\n" * 100, [attachment(4, "신청 제한: 휴업 기업")])
    model = ScriptedEvidenceModel(steps=[
        calls("get_document_content", "get_company_profile"),
        call("search_document_evidence", {"queries": ["신청 제한"]}),
        AIMessage(content="완료"),
    ])
    with pytest.raises(EvidenceCollectionError, match="get_attachment_texts"):
        asyncio.run(AnalysisEvidenceAgent(model).collect(AnalysisToolContext(doc, 100)))


@pytest.mark.parametrize("arguments", [
    {"queries": []}, {"queries": ["x"]}, {"queries": ["가" * 81]},
    {"queries": ["조건"] * 6}, {"queries": ["조건"], "offset": -1},
    {"queries": ["조건"], "source_id": "../../private"},
])
def test_invalid_tool_arguments_are_not_executed(arguments):
    doc, _ = omitted_notice()
    model = ScriptedEvidenceModel(steps=[
        calls("get_document_content", "get_company_profile"),
        call("search_document_evidence", arguments),
        AIMessage(content="완료"),
    ])
    context = AnalysisToolContext(doc, 300)
    _, used = asyncio.run(AnalysisEvidenceAgent(model).collect(context))
    assert "search_document_evidence" not in used
    assert not context.targeted.cache
    assert tool_results(model.seen[-1])[-1].status == "error"


def test_actual_final_analysis_prompt_receives_recovered_condition():
    doc, critical = omitted_notice()
    runner = LangChainAnalysisRunner.__new__(LangChainAnalysisRunner)
    runner._settings = SimpleNamespace(timeout_seconds=5, max_text_chars=300)
    runner._evidence_agent = AnalysisEvidenceAgent(ScriptedEvidenceModel(steps=[
        calls("get_document_content", "get_company_profile"),
        call("search_document_evidence", {"queries": ["신청 제한"]}),
        AIMessage(content="완료"),
    ]))
    runner._assess_legal_risks = AsyncMock(return_value=[])

    class PromptCaptured(Exception):
        pass

    async def capture(messages):
        assert critical in messages[-1]["content"]
        assert "<targeted_search>" in messages[-1]["content"]
        assert "검색어 일치와 원문 확인을 구분" in messages[0]["content"]
        raise PromptCaptured

    runner._analysis_model = SimpleNamespace(ainvoke=capture)
    with pytest.raises(PromptCaptured):
        asyncio.run(runner.analyze(doc))
