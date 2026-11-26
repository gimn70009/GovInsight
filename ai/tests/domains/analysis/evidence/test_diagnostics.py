import asyncio
import logging

from langchain_core.messages import AIMessage

from app.domains.analysis.evidence.agent import AnalysisEvidenceAgent
from app.domains.analysis.evidence.tools import AnalysisToolContext
from tests.domains.analysis.evidence.fakes import ScriptedEvidenceModel, calls
from tests.domains.analysis.evidence.test_targeted import attachment, call, notice, omitted_notice

LOGGER = "app.domains.analysis.evidence.agent"


def run_logged(caplog, context, steps):
    caplog.set_level(logging.INFO, logger=LOGGER)
    logger = logging.getLogger(LOGGER)
    previous_propagate = logger.propagate
    logger.propagate = False
    logger.addHandler(caplog.handler)
    try:
        asyncio.run(AnalysisEvidenceAgent(ScriptedEvidenceModel(steps=steps)).collect(context))
    finally:
        logger.removeHandler(caplog.handler)
        logger.propagate = previous_propagate
    return [record.getMessage() for record in caplog.records if record.name == LOGGER]


def message(messages, prefix):
    return next(item for item in messages if item.startswith(prefix))


def test_short_source_logs_tools_not_offered_and_complete_coverage(caplog):
    text = "제출서류: 신청서와 사업계획서"
    messages = run_logged(caplog, AnalysisToolContext(notice(text), 1000), [
        calls("get_document_content", "get_company_profile"), AIMessage(content="완료"),
    ])
    plan = message(messages, "근거 조사 도구 제공.")
    summary = message(messages, "근거 조사 조회 진단.")
    assert "optional_available=False" in plan
    assert "reason=WITHIN_SOURCE_BUDGET" in plan
    assert "optional_tools=-" in plan
    assert "optional_state=NOT_OFFERED" in summary
    assert f"body_original_chars={len(text)}" in summary
    assert f"body_selected_chars={len(text)}" in summary
    assert "body_truncated=False" in summary
    assert "model_responses=2" in summary
    assert text not in caplog.text


def test_omitted_source_logs_offered_but_not_requested_without_inventing_reason(caplog):
    doc, critical = omitted_notice()
    messages = run_logged(caplog, AnalysisToolContext(doc, 300), [
        calls("get_document_content", "get_company_profile"), AIMessage(content="완료"),
    ])
    plan = message(messages, "근거 조사 도구 제공.")
    summary = message(messages, "근거 조사 조회 진단.")
    assert "optional_available=True" in plan
    assert "reason=BODY_OVER_BUDGET" in plan
    assert "optional_state=NOT_REQUESTED" in summary
    assert "targeted_requested=0 targeted_executed=0 targeted_errors=0" in summary
    assert "body_truncated=True" in summary
    assert "additional_source_chars=0" in summary
    assert critical not in caplog.text


def test_attachment_coverage_does_not_count_duplicates_or_unavailable_files_as_omissions(caplog):
    text = ("공통 안내입니다. " * 100).strip()
    doc = notice("본문 안내", [
        attachment(4, text), attachment(5, text), attachment(6, None),
    ])
    messages = run_logged(caplog, AnalysisToolContext(doc, 100), [
        calls("get_document_content", "get_company_profile", "get_attachment_texts"),
        AIMessage(content="완료"),
    ])
    plan = message(messages, "근거 조사 도구 제공.")
    summary = message(messages, "근거 조사 조회 진단.")
    assert "reason=ATTACHMENTS_OVER_BUDGET" in plan
    assert f"attachment_unique_original_chars={len(text)}" in plan
    assert f"attachment_unique_original_chars={len(text)}" in summary
    assert "attachment_truncated_count=1" in summary
    assert "attachment_unavailable_count=1" in summary
    assert "attachment_duplicate_count=1" in summary


def test_search_logs_status_and_counts_without_query_title_filename_or_source(caplog):
    doc, critical = omitted_notice()
    doc = doc.model_copy(update={
        "title": "PRIVATE_TITLE",
        "original_url": "https://example.org/PRIVATE_URL",
        "content_text": doc.content_text + "\n\n제출서류: PRIVATE_QUERY PRIVATE_SOURCE",
        "attachments": [attachment(4, "PRIVATE_ATTACHMENT", "PRIVATE_FILENAME.pdf")],
    })
    context = AnalysisToolContext(doc, 300)
    messages = run_logged(caplog, context, [
        calls("get_document_content", "get_company_profile", "get_attachment_texts"),
        call("search_document_evidence", {"queries": ["PRIVATE_QUERY"]}),
        AIMessage(content="PRIVATE_INTERNAL_SUMMARY"),
    ])
    summary = message(messages, "근거 조사 조회 진단.")
    result = message(messages, "추가 근거 도구 결과.")
    assert "optional_state=EXECUTED" in summary
    assert "targeted_requested=1 targeted_executed=1 targeted_errors=0" in summary
    assert f"additional_source_chars={context.targeted.selected_chars}" in summary
    assert "tool=search_document_evidence status=OK" in result
    assert "cache_reused=False matches=1 read_required=0" in result
    for private in (
        "PRIVATE_QUERY", "PRIVATE_SOURCE", "PRIVATE_TITLE", "PRIVATE_URL",
        "PRIVATE_ATTACHMENT", "PRIVATE_FILENAME", "PRIVATE_INTERNAL_SUMMARY", critical,
    ):
        assert private not in caplog.text


def test_invalid_arguments_are_logged_as_failed_call_without_raw_validation_error(caplog):
    doc, _ = omitted_notice()
    messages = run_logged(caplog, AnalysisToolContext(doc, 300), [
        calls("get_document_content", "get_company_profile"),
        call("search_document_evidence", {"queries": ["PRIVATE_QUERY" * 20]}),
        AIMessage(content="완료"),
    ])
    summary = message(messages, "근거 조사 조회 진단.")
    assert "optional_state=CALL_FAILED" in summary
    assert "targeted_requested=1 targeted_executed=0 targeted_errors=1" in summary
    assert not any(item.startswith("추가 근거 도구 결과.") for item in messages)
    assert "PRIVATE_QUERY" not in caplog.text


def test_no_match_cached_call_and_limit_status_are_distinguished(caplog):
    # This checks optional calls independently of the mandatory recovery policy.
    doc = notice("일반 안내입니다.\n\n" * 100 + "확인사항: 휴업 중인 기업")
    messages = run_logged(caplog, AnalysisToolContext(doc, 300), [
        calls("get_document_content", "get_company_profile"),
        call("search_document_evidence", {"queries": ["PRIVATE_ABSENT"]}, "first"),
        call("search_document_evidence", {"queries": ["PRIVATE_ABSENT"]}, "repeat"),
        call("search_document_evidence", {"queries": ["확인사항"]}, "second"),
        call("search_document_evidence", {"queries": ["PRIVATE_THIRD"]}, "third"),
        AIMessage(content="완료"),
    ])
    results = [item for item in messages if item.startswith("추가 근거 도구 결과.")]
    assert len(results) == 4
    assert "status=NO_MATCH cache_reused=False matches=0" in results[0]
    assert "status=NO_MATCH cache_reused=True matches=0" in results[1]
    assert "status=OK cache_reused=False matches=1" in results[2]
    assert "status=REQUEST_LIMIT cache_reused=False matches=0" in results[3]
    assert "PRIVATE_ABSENT" not in caplog.text
    assert "PRIVATE_THIRD" not in caplog.text
