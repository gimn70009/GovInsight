import json
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.analysis.schemas.result import (
    PREPARATION_SCHEMA_VERSION,
    ProposalDocumentType,
    RequirementSource,
)
from app.domains.report import enrichment
from app.domains.report.brief import BriefFactsModelOutput, build_context, validate_brief
from app.domains.report.config import ReportBriefSettings
from app.domains.report.submission_documents import reusable_submission_documents
from app.domains.report.template import TemplateReportGenerator, display_units
from tests.domains.report.test_report_brief import output, request, run
from tests.domains.report.test_report_template import with_document


@pytest.fixture(autouse=True)
def clear_cache():
    enrichment._CACHE.clear()
    yield
    enrichment._CACHE.clear()


def ready_request(items=None):
    req = request(checklist=items or ["사업계획서", "결산재무제표"])
    proposal = req.documents[0].proposal
    proposal.document_type = ProposalDocumentType.PROPOSAL_REQUEST
    proposal.preparation_schema_version = PREPARATION_SCHEMA_VERSION
    for item in proposal.preparation.submission_documents:
        item.source = RequirementSource(
            origin="NOTICE_BODY",
            section_title="제출서류",
            excerpt=f"제출서류로 {item.title}를 준비해야 합니다.",
        )
    # Validate fixtures exactly as a backend report request, including source objects.
    return type(req).model_validate(req.model_dump(by_alias=True))


def documents(req):
    return req.documents[0].proposal.preparation.submission_documents


def brief(req, result=None):
    doc = req.documents[0]
    return validate_brief(result or output(), build_context(doc, 16000), doc)


def test_latest_checklist_wins_even_if_report_model_omits_or_adds_documents():
    req = ready_request()
    for result in [output(), output(documents=[])]:
        actual = brief(req, result)
        assert [item.title for item in actual.documents] == ["사업계획서", "결산재무제표"]
        assert actual.uses_saved_checklist
        assert actual.note is None
        assert actual.facts.contact == "사업지원팀 02-0000-0000"
        body = TemplateReportGenerator().generate(req, briefs={2: actual}).summary
        assert "결산재무제표" in body
        assert "정관 사본" not in body


@pytest.mark.parametrize("case", ["old", "failed", "no_source", "empty_quote", "attachment_name"])
def test_unusable_checklist_keeps_the_source_extraction_path(case):
    req = ready_request()
    if case == "old":
        req.documents[0].proposal.preparation_schema_version = PREPARATION_SCHEMA_VERSION - 1
    elif case == "failed":
        req.documents[0].proposal.draft_status = "REVIEW_REQUIRED"
    elif case == "no_source":
        documents(req)[1].source = None
    elif case == "empty_quote":
        documents(req)[1].source.excerpt = "....."
    else:
        documents(req)[1].source.origin = "ATTACHMENT"
        documents(req)[1].source.attachment_name = None
    assert reusable_submission_documents(req.documents[0]) is None
    context = json.loads(build_context(req.documents[0], 16000).payload)
    assert not context["reuse_submission_checklist"]
    assert [item.title for item in brief(req).documents] == [
        "제4회 혁신대상 포상 신청서",
        "정관 사본",
    ]


def test_latest_checklist_without_application_rows_still_extracts_documents():
    req = ready_request([{"title": "협약서", "stage": "AGREEMENT"}])
    assert reusable_submission_documents(req.documents[0]) is None
    assert not brief(req).uses_saved_checklist
    assert len(brief(req).documents) == 2


def test_report_shows_names_only_while_checklist_metadata_remains_available():
    req = ready_request(
        [
            {
                "title": "운영계획서",
                "detail": "사업계획서에 포함하는 별첨입니다.",
                "appliesTo": "대학이 제출하는 기업 작성자료",
            },
            {
                "title": "사업자등록증",
                "requirementLevel": "CONDITIONAL",
                "appliesTo": "최초 참여 또는 등록사항 변경 시",
                "detail": "기업이 대학에 제출하며 변경이 없는 기존 참여기업은 제출하지 않습니다.",
            },
            {"title": "추가 실적 자료", "requirementLevel": "OPTIONAL"},
            {"title": "회사 소개서", "requirementLevel": "RECOMMENDED"},
            {"title": "협약서", "stage": "AGREEMENT"},
            {"title": "결과보고서", "stage": "REPORTING"},
        ]
    )
    result = brief(req)
    body = TemplateReportGenerator().generate(req, briefs={2: result}).summary
    assert len(result.documents) == 4
    assert [line for line in body.splitlines() if line.startswith("• ")][-4:] == [
        "• 운영계획서",
        "• 사업자등록증",
        "• 추가 실적 자료",
        "• 회사 소개서",
    ]
    assert "대상·조건:" not in body and " — " not in body
    assert "최초 참여 또는 등록사항 변경 시" not in body
    assert "사업계획서에 포함하는 별첨입니다." not in body
    assert result.documents[1].requirement_level == "CONDITIONAL"
    assert result.documents[1].applies_to == "최초 참여 또는 등록사항 변경 시"
    assert result.documents[0].detail == "사업계획서에 포함하는 별첨입니다."
    assert "협약서" not in body and "결과보고서" not in body


def test_report_deduplicates_identical_names_without_changing_saved_roles():
    req = ready_request(
        [
            {"title": "확인서", "appliesTo": "주관기관"},
            {"title": "확인서", "appliesTo": "공동기관"},
        ]
    )
    body = TemplateReportGenerator().generate(req, briefs={2: brief(req)}).summary
    assert body.count("• 확인서") == 1
    assert "주관기관" not in body and "공동기관" not in body
    assert [item.applies_to for item in documents(req)] == ["주관기관", "공동기관"]


def test_saved_citations_are_not_rejected_by_truncated_report_excerpts():
    req = ready_request()
    req.documents[0].content_text = "사업 목적만 전달된 짧은 발췌입니다."
    req.documents[0].attachments = []
    assert [item.title for item in brief(req).documents] == ["사업계획서", "결산재무제표"]


def test_facts_only_schema_and_prompt_omit_document_generation(monkeypatch):
    import asyncio

    facts_model = SimpleNamespace(
        ainvoke=AsyncMock(
            return_value={
                "parsed": BriefFactsModelOutput(
                    applicants=[], deadlines=[], destinations=[], contacts=[]
                ),
            }
        )
    )
    full_model = SimpleNamespace(ainvoke=AsyncMock(return_value={"parsed": output()}))
    schemas = []

    class Model:
        def with_structured_output(self, schema, **kwargs):
            schemas.append(schema)
            return facts_model if schema is BriefFactsModelOutput else full_model

    monkeypatch.setattr(enrichment, "ChatOpenAI", lambda **kwargs: Model())
    runner = enrichment.SmallModelBriefRunner(ReportBriefSettings(api_key="test-placeholder"))
    req = ready_request()
    context = build_context(req.documents[0], 16000)
    assert "결산재무제표" not in context.payload
    result = asyncio.run(runner.extract(context))
    assert result.documents == []
    assert "documents" not in BriefFactsModelOutput.model_json_schema()["properties"]
    facts_model.ainvoke.assert_awaited_once()
    full_model.ainvoke.assert_not_awaited()
    messages = facts_model.ainvoke.call_args.args[0]
    assert "자격·기한·접수 방법·문의처만" in messages[0][1]
    assert "documents에" not in messages[0][1]
    assert "이전 분석의 서류 후보" not in messages[1][1]
    asyncio.run(runner.extract(build_context(request().documents[0], 16000)))
    full_model.ainvoke.assert_awaited_once()
    assert "documents에" in full_model.ainvoke.call_args.args[0][0][1]
    assert len(schemas) == 2


def test_cached_facts_use_current_checklist_conditions_and_urls():
    req = ready_request(["사업계획서"])
    req.documents[0].attachments = (
        with_document(
            attachments=[
                {
                    "fileName": "사업계획서.hwp",
                    "downloadUrl": "https://example.go.kr/old",
                }
            ]
        )
        .documents[0]
        .attachments
    )
    runner = SimpleNamespace(extract=AsyncMock(return_value=output(documents=[])))
    first = run(req, runner)[2]
    documents(req)[0].applies_to = "공동기관만 제출"
    documents(req)[0].detail = "주관기관 사업계획서에 포함하여 제출합니다."
    req.documents[0].attachments[0].download_url = "https://example.go.kr/current"
    second = run(req, runner)[2]
    runner.extract.assert_awaited_once()
    assert first.documents[0].applies_to != second.documents[0].applies_to
    assert second.documents[0].applies_to == "공동기관만 제출"
    assert second.documents[0].form.url == "https://example.go.kr/current"
    assert "포함하여" in second.documents[0].detail


def test_switching_between_reuse_and_extraction_does_not_share_cache():
    req = ready_request()
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    assert run(req, runner)[2].uses_saved_checklist
    req.documents[0].proposal.preparation_schema_version -= 1
    assert not run(req, runner)[2].uses_saved_checklist
    assert runner.extract.await_count == 2


@pytest.mark.parametrize("mode", ["disabled", "failure", "timeout", "no_source"])
def test_unavailable_report_model_still_displays_only_saved_names(mode):
    req = ready_request(
        [
            {
                "title": "사업자등록증",
                "requirementLevel": "CONDITIONAL",
                "appliesTo": "최초 참여 기업만 제출",
            }
        ]
    )
    settings = ReportBriefSettings()
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    if mode == "disabled":
        settings = replace(settings, enabled=False)
    elif mode == "no_source":
        req.documents[0].content_text = None
        req.documents[0].attachments = []
    else:
        runner.extract.side_effect = TimeoutError() if mode == "timeout" else ValueError("failure")
    body = TemplateReportGenerator().generate(req, briefs=run(req, runner, settings)).summary
    assert "• 사업자등록증" in body and "최초 참여 기업만 제출" not in body
    assert "조건부" not in body


def test_long_details_stay_out_of_report_and_omissions_point_to_checklist():
    detail = (
        "사업계획서에 포함하는 자료이며 "
        + "조건 확인이 필요합니다. " * 25
        + "변경이 없으면 면제됩니다."
    )
    req = ready_request([{"title": f"서류{i}확인서", "detail": detail} for i in range(8)])
    body = TemplateReportGenerator().generate(req, briefs={2: brief(req)}).summary
    assert "사업 제안 체크리스트에서 확인" in body
    assert "변경이 없으면 면제됩니다." not in body
    assert "조건 확인이 필요합니다." not in body
    assert display_units(body) <= 3900
