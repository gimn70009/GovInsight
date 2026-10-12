"""Realistic malformed model outputs must recover from sources or stay observable."""

import json
import logging
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.report import enrichment
from app.domains.report.brief import (
    BriefFactsModelOutput,
    BriefOutput,
    build_context,
    fallback_brief,
    validate_brief,
)
from app.domains.report.quality import schedule_consistency_issues
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_brief import output, request, run, sourced
from tests.domains.report.test_report_template import with_document


def test_rejected_deadline_rewrite_recovers_verified_source_even_with_display_text():
    req = request(summary="접수 마감은 2026년 10월 14일입니다.")
    bad = sourced("2026년 10월 14일(수) 18:00까지 접수에 限(온라인·오프라인 포함)")
    bad["display_text"] = "접수 마감: 2027년 12월 31일 18:00까지"
    doc = req.documents[0]
    result = validate_brief(output(deadlines=[bad]), build_context(doc, 16000), doc)
    assert "2026" in result.facts.deadline and "14" in result.facts.deadline
    assert "2027" not in result.facts.deadline
    assert "deadline:unsafe_display" in result.validation_issues
    assert "deadline" in result.recovered_fields


def test_wrong_purpose_route_and_issuer_contact_cannot_survive_final_assembly():
    bad_route = "중복성 제기 접수처: 서울시 예시로 10. 방법: 대표자 명의 공문 제출"
    issuer = "공고기관: 예시부 (공고일 2026년 10월 12일)"
    source = ("1. 신청 방법\n신청방법: IRIS 온라인 접수(https://example.org/apply)\n"
              "2. 문의처\n문의처: 사업지원팀 02-0000-0000\n"
              "3. 중복성 제기\n" + bad_route + "\n" + issuer)
    req = with_document(title="2026년 연구개발 신규과제 공고", contentText=source)
    doc = req.documents[0]
    model = BriefOutput(applicants=[], deadlines=[], documents=[],
                        destinations=[sourced(bad_route)], contacts=[sourced(issuer)])
    brief = validate_brief(model, build_context(doc, 16000), doc)
    assert "중복성" not in brief.facts.destination
    assert "IRIS" in brief.facts.destination
    assert "공고기관" not in brief.facts.contact
    assert "02-0000-0000" in brief.facts.contact
    assert "destination:wrong_purpose" in brief.validation_issues
    assert "contact:wrong_purpose" in brief.validation_issues


def test_invalid_field_is_partial_and_is_not_cached(caplog, monkeypatch):
    enrichment._CACHE.clear()
    monkeypatch.setattr(enrichment.logger, "propagate", False)
    enrichment.logger.addHandler(caplog.handler)
    caplog.set_level(logging.INFO, logger=enrichment.logger.name)
    bad = sourced("근거 없는 신청기관", quote="없는 원문")
    bad["display_text"] = "근거 없는 신청기관"
    runner = SimpleNamespace(extract=AsyncMock(return_value=output(applicants=[bad])))
    try:
        first = run(runner=runner)
        run(runner=runner)
        assert first[2].validation_issues
        assert runner.extract.await_count == 2
        assert not enrichment._CACHE
        events = [json.loads(r.getMessage().split(" ", 1)[1]) for r in caplog.records
                  if r.getMessage().startswith("report_brief_batch_finish ")]
        assert all(e["partial_count"] == 1 and e["degraded"] for e in events)
        assert "근거 없는 신청기관" not in caplog.text
    finally:
        enrichment.logger.removeHandler(caplog.handler)
        enrichment._CACHE.clear()


def test_summary_conflict_is_explicit_and_never_overwrites_source_deadline():
    req = request(summary="신청 마감은 2026년 12월 31일입니다.")
    doc = req.documents[0]
    brief = validate_brief(output(), build_context(doc, 16000), doc)
    assert "deadline:summary_conflict" in brief.validation_issues
    body = TemplateReportGenerator().generate(req, briefs={2: brief}).summary
    assert "요약과 제출 안내의 일정이 다릅니다" in body
    assert "14" in brief.facts.deadline and "31" not in brief.facts.deadline


def test_summary_is_not_used_to_invent_missing_deadlines():
    doc = with_document(summary="신청 마감은 2026년 12월 31일입니다.",
                        contentText="공고 본문에 접수 일정이 없습니다.").documents[0]
    model = BriefOutput(applicants=[], deadlines=[], destinations=[], contacts=[], documents=[])
    brief = validate_brief(model, build_context(doc, 16000), doc)
    assert brief.facts.deadline == "원문 확인 필요"
    assert "deadline:summary_without_verified_schedule" in brief.validation_issues


@pytest.mark.parametrize("summary", [
    "신청기간은 2026년 10월 7일부터 2026년 10월 26일까지입니다.",
    "신청기간은 10.7.부터 10.26.까지입니다.",
])
def test_summary_period_and_missing_year_do_not_create_false_conflicts(summary):
    doc = with_document(summary=summary).documents[0]
    assert schedule_consistency_issues(doc, "접수 마감: 2026년 10월 26일") == ()


def test_oversized_model_excerpt_is_recorded_before_field_validation():
    quote = "신청 대상은 기업입니다. " * 20
    out = BriefFactsModelOutput.model_validate({
        "applicants": [{"evidence": {"source_id": "source-0", "quote": quote},
                        "fragments": [], "display_text": "신청 대상: 기업"}],
        "deadlines": [], "destinations": [], "contacts": [],
    }).extracted()
    assert out.applicants == []
    assert out.prevalidation_issues == ["applicant:oversized_model_excerpt"]


def test_matching_opening_date_does_not_hide_conflicting_closing_date():
    doc = with_document(
        summary="신청기간은 2026년 10월 1일부터 2026년 11월 30일까지입니다.",
    ).documents[0]
    assert schedule_consistency_issues(
        doc, "신청기간: 2026년 10월 1일부터 2026년 10월 31일까지",
    ) == ("deadline:summary_conflict",)


def test_disabled_or_failed_model_never_promotes_unsourced_saved_comparison():
    req = with_document(
        summary="신청 마감은 2026년 12월 31일입니다.",
        contentText="연구개발 지원 사업을 진행합니다. 자세한 사항은 추후 안내합니다.",
        comparisonSummary={"applicationDeadline": "접수 마감: 2026년 12월 31일",
                           "eligibility": "지원대상: 국내 중소기업"},
    )
    brief = fallback_brief(req.documents[0], "제출 안내 자동 정리 실패")
    assert brief.facts.deadline == brief.facts.applicant == "원문 확인 필요"
    assert brief.validation_issues == ("deadline:summary_without_verified_schedule",)
    body = TemplateReportGenerator().generate(req).summary
    assert "요약에 적힌 제출 기한은 원문 근거를 확인하지 못했습니다" in body
    assert "제출·의견 기한: 원문 확인 필요" in body
    assert "국내 중소기업" not in body


def test_unsupported_submission_document_is_not_cached_as_success():
    enrichment._CACHE.clear()
    bad_output = output()
    bad_output.documents[0].title = "원문에 없는 서류"
    runner = SimpleNamespace(extract=AsyncMock(return_value=bad_output))
    try:
        first = run(runner=runner)[2]
        run(runner=runner)
        assert "documents:unsupported_title" in first.validation_issues
        assert runner.extract.await_count == 2
        assert not enrichment._CACHE
    finally:
        enrichment._CACHE.clear()


def test_display_rewrite_cannot_turn_application_route_into_objection_route():
    source = "신청방법: 온라인 접수 https://example.org/apply"
    doc = with_document(contentText=source).documents[0]
    bad = sourced(source)
    bad["display_text"] = "중복성 제기 접수처: 온라인 접수 https://example.org/apply"
    model = BriefOutput(applicants=[], deadlines=[], contacts=[], documents=[], destinations=[bad])
    brief = validate_brief(model, build_context(doc, 16000), doc)
    assert "중복성" not in brief.facts.destination
    assert "https://example.org/apply" in brief.facts.destination
    assert "destination:wrong_display_purpose" in brief.validation_issues


def test_objection_deadline_is_not_reintroduced_by_source_fallback():
    req = with_document(title="2026년 연구개발 신규과제 공고", contentText=(
        "1. 신청 방법\n신청방법: 온라인 접수\n"
        "2. 중복성 제기\n접수기간: 2026.10.20.까지\n접수처: 우편 접수"
    ))
    brief = fallback_brief(req.documents[0], "제출 안내 자동 정리 실패")
    assert brief.facts.deadline == "원문 확인 필요"
    assert "2026년 10월 20일" not in TemplateReportGenerator().generate(req).summary
