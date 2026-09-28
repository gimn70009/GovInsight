import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.report import enrichment
from app.domains.report.brief import BriefOutput, build_context, validate_brief
from app.domains.report.config import ReportBriefSettings
from app.domains.report.fact_reuse import verified_submission_facts
from tests.domains.report.test_report_brief import output, run
from tests.domains.report.test_report_checklist_reuse import ready_request
from tests.domains.report.test_report_template import with_document

SOURCE = (
    "신청대상: 국내 중소기업\n"
    "접수기간: 2026-10-14 18:00까지 (도착분 기준)\n"
    "접수방법: 온라인 접수 후 원본 우편 제출\n"
    "문의처: 사업지원팀 02-1234-5678\n"
)


def request(source=SOURCE, *, saved=False):
    req = ready_request() if saved else with_document()
    doc = req.documents[0]
    doc.content_text = source
    doc.attachments = []
    doc.comparison_summary = with_document(comparisonSummary={
        "eligibility": "국내 중소기업",
        "applicationDeadline": "2026-10-14 18:00 도착분 기준",
    }).documents[0].comparison_summary
    return req


@pytest.fixture(autouse=True)
def cache():
    enrichment._CACHE.clear()
    yield
    enrichment._CACHE.clear()


def test_current_source_facts_leave_only_documents_for_model():
    req = request()
    ctx = build_context(req.documents[0], 16000)
    assert json.loads(ctx.payload)["requested_fields"] == ["documents"]
    assert ctx.reused_facts["destination"] == "온라인 접수 후 원본 우편 제출"
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    brief = run(req, runner)[2]
    runner.extract.assert_awaited_once()
    assert brief.facts.contact == "사업지원팀 02-1234-5678"
    assert "도착분 기준" in brief.facts.deadline


def test_all_facts_and_checklist_skip_model_even_without_api_key(monkeypatch):
    monkeypatch.setattr(enrichment, "ChatOpenAI", lambda **kwargs: pytest.fail("model created"))
    req = request(saved=True)
    result = run(req, settings=ReportBriefSettings(api_key=None))[2]
    assert result.uses_saved_checklist and len(result.documents) == 2
    assert result.note is None
    assert result.facts.applicant == "국내 중소기업"


@pytest.mark.parametrize("change", [
    "stale", "missing", "conflict", "same_line_conflict", "reference", "form",
    "truncated", "incomplete", "lost_condition", "invalid_date", "stage", "schedule",
])
def test_uncertain_deadline_is_left_for_model(change):
    req = request()
    doc = req.documents[0]
    if change == "stale":
        doc.comparison_summary.application_deadline = "2025-10-14"
    elif change == "missing":
        doc.comparison_summary.application_deadline = None
    elif change in {"conflict", "same_line_conflict"}:
        separator = "\n" if change == "conflict" else " "
        doc.content_text = SOURCE.replace(
            "\n접수방법", separator + "접수기간: 2026-11-14\n접수방법"
        )
    elif change in {"reference", "form"}:
        doc.content_text = "제목만 있습니다."
        doc.attachments = with_document(attachments=[{
            "fileName": "참고자료.zip" if change == "reference" else "신청서 양식.hwp",
            "downloadUrl": "https://example.go.kr/form",
            "extractedText": "[파일: 공고문.hwp]\n" + SOURCE,
        }]).documents[0].attachments
    elif change == "truncated":
        doc.content_text += "[일부 원문 생략: 파일 뒷부분 미전달]"
    elif change == "incomplete":
        doc.content_text = SOURCE.replace("(도착분 기준)", "(도착분 기준")
    elif change == "lost_condition":
        doc.content_text = SOURCE.replace("(도착분 기준)", "")
    elif change == "invalid_date":
        doc.content_text = SOURCE.replace("2026-10-14", "2026-02-30")
        doc.comparison_summary.application_deadline = "2026-02-30"
    elif change == "stage":
        doc.content_text = "선정 후:\n" + SOURCE
    elif change == "schedule":
        doc.content_text = SOURCE.replace("(도착분 기준)", "평가결과 통보 및 협약체결")
    ctx = build_context(doc, 16000)
    assert "deadline" not in ctx.reused_facts
    assert "deadlines" in json.loads(ctx.payload)["requested_fields"]


def test_identical_wrapped_sources_are_deduplicated():
    req = request()
    doc = req.documents[0]
    doc.attachments = with_document(attachments=[{
        "fileName": "공고문.hwp", "downloadUrl": "https://example.go.kr/notice",
        "extractedText": SOURCE.replace("18:00까지 ", "18:00까지\n"),
    }]).documents[0].attachments
    assert verified_submission_facts(doc)["deadline"] == "2026-10-14 18:00까지 (도착분 기준)"


def test_source_proof_survives_smaller_model_budget_and_failure():
    req = request()
    ctx = build_context(req.documents[0], 20)
    assert len(ctx.reused_facts) == 4
    runner = SimpleNamespace(extract=AsyncMock(side_effect=TimeoutError()))
    brief = run(req, runner)[2]
    assert "도착분 기준" in brief.facts.deadline
    assert brief.facts.destination == "온라인 접수 후 원본 우편 제출"


def test_cache_rechecks_current_proof_and_invalidates_changed_request():
    req = request()
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    first = run(req, runner)[2]
    second = run(req, runner)[2]
    assert first == second
    runner.extract.assert_awaited_once()
    req.documents[0].content_text = SOURCE.replace("02-1234-5678", "02-2222-3333")
    third = run(req, runner)[2]
    assert third.facts.contact == "사업지원팀 02-2222-3333"
    assert runner.extract.await_count == 2
    req.documents[0].comparison_summary.application_deadline = "2025-10-14"
    run(req, runner)
    assert runner.extract.await_count == 3
    assert "deadlines" in json.loads(runner.extract.call_args.args[0].payload)["requested_fields"]


def test_partial_schema_and_prompt_only_request_missing_fields(monkeypatch):
    schemas = []
    calls = []

    class Model:
        def with_structured_output(self, schema, **kwargs):
            schemas.append(schema)

            async def invoke(messages):
                calls.append(messages)
                return {"parsed": schema.model_validate({"documents": []})}

            return SimpleNamespace(ainvoke=invoke)

    monkeypatch.setattr(enrichment, "ChatOpenAI", lambda **kwargs: Model())
    runner = enrichment.SmallModelBriefRunner(ReportBriefSettings(api_key="test"))
    ctx = build_context(request().documents[0], 16000)
    result = asyncio.run(runner.extract(ctx))
    assert result == BriefOutput(
        applicants=[], deadlines=[], destinations=[], contacts=[], documents=[]
    )
    assert list(schemas[-1].model_json_schema()["properties"]) == ["documents"]
    assert "이번 응답에는 documents 항목만" in calls[0][0][1]
    for instruction in (
        "applicants:", "deadlines:", "destinations:", "contacts:", "표 원문:", "문의 원문:",
        "display_text:", "fragments:",
    ):
        assert instruction not in calls[0][0][1]
    asyncio.run(runner.extract(ctx))
    assert len(schemas) == 3  # Two compatibility schemas and one reused partial schema.


def test_unrequested_model_values_cannot_override_verified_facts():
    req = request()
    doc = req.documents[0]
    ctx = build_context(doc, 16000)
    brief = validate_brief(output(), ctx, doc)
    assert brief.facts.deadline == "2026-10-14 18:00까지 (도착분 기준)"
    assert brief.facts.contact == "사업지원팀 02-1234-5678"


@pytest.mark.parametrize("condition", [
    "신청기한까지 우편 도착한 서류에 한해 인정",
    "우편은 마감일 소인분까지 유효",
    "예산 소진 시 조기마감",
    "양국에 동시에 접수해야 함",
])
def test_unlabelled_deadline_conditions_block_date_only_reuse(condition):
    req = request(SOURCE.replace("(도착분 기준)", "") + "\n" + condition)
    req.documents[0].comparison_summary.application_deadline = "2026-10-14"
    assert "deadline" not in verified_submission_facts(req.documents[0])


def test_unknown_requested_fields_are_rejected(monkeypatch):
    class Model:
        def with_structured_output(self, schema, **kwargs):
            return SimpleNamespace(ainvoke=AsyncMock(return_value={"parsed": {"contacts": []}}))

    monkeypatch.setattr(enrichment, "ChatOpenAI", lambda **kwargs: Model())
    runner = enrichment.SmallModelBriefRunner(ReportBriefSettings(api_key="test"))
    with pytest.raises(ValueError, match="omitted a requested field"):
        asyncio.run(runner.extract(build_context(request().documents[0], 16000)))
