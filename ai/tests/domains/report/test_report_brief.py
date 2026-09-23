import asyncio
import json
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.domains.report import enrichment, tasks
from app.domains.report.brief import BriefOutput, build_context, validate_brief
from app.domains.report.config import ReportBriefSettings
from app.domains.report.facts import submission_facts
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_template import with_document

DEADLINE = "2026년 10월 14일(수) 18:00까지 접수에 限(온라인·오프라인 포함)"
METHOD = "온라인(이메일) 제출 후 원본 날인 후 우편발송(또는 방문접수)"
SOURCE = (
    "신청대상: 개인 및 단체(기업)\n"
    f"제출기한: {DEADLINE} - 제출방법 : {METHOD} "
    "※ 붙임 1. 제4회 혁신대상 포상 신청서 1부. 2. 공적조서 1부.\n"
    "제출서류: 제4회 혁신대상 포상 신청서, 정관 사본(법인에 한함)\n"
    "문의처: 사업지원팀 02-0000-0000"
)


def request(**updates):
    return with_document(
        contentText=SOURCE,
        attachments=[
            {
                "fileName": "제4회 혁신대상 포상 신청서.hwp",
                "downloadUrl": "https://example.go.kr/form?id=1",
            }
        ],
        **updates,
    )


def sourced(text, quote=None, source_id="source-0"):
    return {"text": text, "evidence": {"source_id": source_id, "quote": quote or text}}


def output(**updates):
    data = {
        "applicants": [sourced("개인 및 단체(기업)")],
        "deadlines": [sourced(DEADLINE)],
        "destinations": [sourced(METHOD)],
        "contacts": [sourced("사업지원팀 02-0000-0000")],
        "documents": [
            {
                "title": "제4회 혁신대상 포상 신청서",
                "condition": None,
                "evidence": {
                    "source_id": "source-0",
                    "quote": "제출서류: 제4회 혁신대상 포상 신청서, 정관 사본(법인에 한함)",
                },
                "form_source_id": "source-1",
            },
            {
                "title": "정관 사본",
                "condition": "법인에 한함",
                "evidence": {
                    "source_id": "source-0",
                    "quote": "제출서류: 제4회 혁신대상 포상 신청서, 정관 사본(법인에 한함)",
                },
                "form_source_id": None,
            },
        ],
    }
    data.update(updates)
    return BriefOutput.model_validate(data)


def validate(result=None, req=None):
    doc = (req or request()).documents[0]
    return validate_brief(result or output(), build_context(doc, 16000), doc)


@pytest.fixture(autouse=True)
def clear_cache():
    enrichment._CACHE.clear()
    yield
    enrichment._CACHE.clear()


def test_no_proposal_still_gets_clean_facts_and_linked_required_documents():
    req = request()
    assert req.documents[0].proposal is None
    brief = validate(req=req)
    assert brief.facts.deadline == DEADLINE
    assert brief.facts.destination == METHOD
    assert [i.title for i in brief.documents] == [
        "제4회 혁신대상 포상 신청서",
        "정관 사본 (법인에 한함)",
    ]
    assert brief.documents[0].form.url == "https://example.go.kr/form?id=1"
    assert brief.documents[1].form is None
    body = TemplateReportGenerator().generate(req, briefs={2: brief}).summary
    deadline_line = next(line for line in body.splitlines() if "• 제출·의견 기한:" in line)
    assert METHOD not in deadline_line and "신청서" not in deadline_line
    assert "[제4회 혁신대상 포상 신청서](https://example.go.kr/form?id=1)" in body
    assert "• 정관 사본 (법인에 한함)" in body
    assert "미작성" not in body and "원문 확인 필요" not in body


def test_existing_checklist_cannot_override_verified_source_documents():
    brief = validate(req=request(checklist=["사업계획서", "납세증명서"]))
    assert [i.title for i in brief.documents] == [
        "제4회 혁신대상 포상 신청서",
        "정관 사본 (법인에 한함)",
    ]


def test_empty_application_checklist_does_not_block_model_extraction():
    req = request(checklist=[{"title": "협약서", "stage": "AGREEMENT"}])
    assert len(validate(req=req).documents) == 2


@pytest.mark.parametrize(
    "field,attribute",
    [
        ("applicants", "applicant"),
        ("deadlines", "deadline"),
        ("destinations", "destination"),
        ("contacts", "contact"),
    ],
)
def test_generated_or_cross_source_claim_is_removed(field, attribute):
    bad = sourced("2026-12-31", quote=DEADLINE)
    expected = getattr(submission_facts(request().documents[0], source_only=True), attribute)
    assert getattr(validate(output(**{field: [bad]})).facts, attribute) == expected
    bad = sourced(DEADLINE, source_id="source-1")
    expected = getattr(submission_facts(request().documents[0], source_only=True), attribute)
    assert getattr(validate(output(**{field: [bad]})).facts, attribute) == expected


def test_deadline_cannot_include_method_even_when_quote_is_real():
    mixed = DEADLINE + " - 제출방법 : " + METHOD
    assert validate(output(deadlines=[sourced(mixed)])).facts.deadline == DEADLINE


@pytest.mark.parametrize(
    "change",
    [
        {"title": "허위 신청서"},
        {"condition": "중소기업만"},
        {"evidence": {"source_id": "source-0", "quote": "없는 제출서류입니다"}},
    ],
)
def test_unsupported_document_title_condition_or_quote_is_removed(change):
    doc = output().documents[0].model_dump()
    doc.update(change)
    assert validate(output(documents=[doc])).documents == []


def test_attachment_name_alone_is_not_a_submission_requirement():
    req = with_document(
        contentText="자료를 안내합니다.",
        attachments=[
            {
                "fileName": "신청서.hwp",
                "downloadUrl": "https://example.go.kr/a",
                "extractedText": "신청서\n기관명: ",
            }
        ],
    )
    item = output().documents[0].model_dump()
    item.update(title="신청서", evidence={"source_id": "source-1", "quote": "신청서\n기관명: "})
    assert validate(output(documents=[item]), req).documents == []


def test_wrong_form_id_does_not_produce_unrelated_or_fabricated_link():
    item = output().documents[0].model_dump()
    item["form_source_id"] = "source-unknown"
    assert validate(output(documents=[item])).documents[0].form is None
    item["download_url"] = "https://evil.example/form"
    with pytest.raises(ValidationError):
        output(documents=[item])


def test_zip_member_uses_only_actual_archive_url():
    req = with_document(
        contentText="제출서류: 신청서",
        attachments=[
            {
                "fileName": "양식.zip",
                "downloadUrl": "https://example.go.kr/forms.zip",
                "extractedText": "[파일: 신청서.hwp]\n신청서\n기관명: ",
            }
        ],
    )
    item = output().documents[0].model_dump()
    item.update(title="신청서", evidence={"source_id": "source-0", "quote": "제출서류: 신청서"})
    brief = validate(output(documents=[item]), req)
    assert brief.documents[0].form.url == "https://example.go.kr/forms.zip"
    assert (
        "[신청서 (ZIP)](https://example.go.kr/forms.zip)"
        in TemplateReportGenerator().generate(req, briefs={2: brief}).summary
    )


def test_prompt_context_uses_bounded_source_excerpts_and_no_download_url():
    doc = with_document(contentText=("배경 소개입니다.\n" * 2000) + SOURCE).documents[0]
    context = build_context(doc, 1200)
    data = json.loads(context.payload)
    assert sum(len(i.text) for i in context.sources.values()) <= 1200
    assert data["sources"][0]["coverage"]["truncated"]
    context = build_context(request().documents[0], 16000)
    assert "https://example.go.kr/form" not in context.payload


def test_fallback_splits_adjacent_deadline_method_and_attachment_fields():
    facts = submission_facts(request().documents[0])
    assert facts.deadline == DEADLINE
    assert facts.destination == METHOD


def run(req=None, runner=None, settings=None):
    return asyncio.run(
        enrichment.prepare_report_briefs(
            req or request(),
            runner=runner,
            settings=settings or ReportBriefSettings(),
        )
    )


def test_cache_reuses_result_but_resolves_current_collected_url():
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    run(runner=runner)
    req = request()
    doc = req.documents[0]
    changed = doc.attachments[0].model_copy(
        update={"download_url": "https://example.go.kr/renewed"}
    )
    req = req.model_copy(update={"documents": [doc.model_copy(update={"attachments": [changed]})]})
    result = run(req, runner)
    assert runner.extract.await_count == 1
    assert result[2].documents[0].form.url == "https://example.go.kr/renewed"


def test_source_model_and_saved_checklist_changes_invalidate_cache():
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    run(runner=runner)
    run(with_document(contentText=SOURCE + "\n변경 안내"), runner)
    run(runner=runner, settings=replace(ReportBriefSettings(), model="other-model"))
    run(request(checklist=["사업계획서"]), runner)
    assert runner.extract.await_count == 4


def test_failure_is_not_cached_and_does_not_prevent_report():
    runner = SimpleNamespace(extract=AsyncMock(side_effect=[RuntimeError("secret"), output()]))
    first = run(runner=runner)
    assert first[2].note == "제출 안내 자동 정리 실패"
    assert "secret" not in TemplateReportGenerator().generate(request(), briefs=first).summary
    assert run(runner=runner)[2].note is None
    assert runner.extract.await_count == 2


def test_disabled_missing_key_and_empty_source_do_not_call_model():
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    assert run(runner=runner, settings=replace(ReportBriefSettings(), enabled=False)) == {}
    assert run()[2].note == "제출 안내 자동 정리 미실행"
    assert run(with_document(), runner)[2].note == "제출 안내 원문 부족"
    runner.extract.assert_not_called()


def test_duplicates_share_call_and_concurrency_is_bounded():
    async def scenario():
        active = peak = calls = 0

        async def extract(context):
            nonlocal active, peak, calls
            calls += 1
            active += 1
            peak = max(peak, active)
            await asyncio.sleep(0.005)
            active -= 1
            return output()

        req = request()
        doc = req.documents[0]
        docs = [
            doc.model_copy(update={"version_id": i, "title": f"공고 {i // 2}"}) for i in range(8)
        ]
        result = await enrichment.prepare_report_briefs(
            req.model_copy(update={"documents": docs}),
            settings=replace(ReportBriefSettings(), concurrency=2),
            runner=SimpleNamespace(extract=extract),
        )
        assert len(result) == 8 and calls == 4 and peak == 2

    asyncio.run(scenario())


@pytest.mark.parametrize("total,per_document", [(0.02, 5), (5, 0.02)])
def test_timeouts_cancel_pending_calls_and_keep_successful_documents(total, per_document):
    async def scenario():
        cancelled = asyncio.Event()

        async def extract(context):
            if json.loads(context.payload)["title"] == "slow":
                try:
                    await asyncio.sleep(10)
                finally:
                    cancelled.set()
            return output()

        req = request()
        doc = req.documents[0]
        req = req.model_copy(
            update={"documents": [doc, doc.model_copy(update={"version_id": 3, "title": "slow"})]}
        )
        result = await enrichment.prepare_report_briefs(
            req,
            settings=replace(
                ReportBriefSettings(), total_timeout_seconds=total, timeout_seconds=per_document
            ),
            runner=SimpleNamespace(extract=extract),
        )
        assert result[2].note is None and result[3].note is not None
        assert cancelled.is_set()

    asyncio.run(scenario())


def test_small_model_uses_strict_schema_and_no_automatic_retries(monkeypatch):
    factory = Mock()
    factory.return_value.with_structured_output.return_value.ainvoke = AsyncMock(
        return_value={"parsed": output()}
    )
    monkeypatch.setattr(enrichment, "ChatOpenAI", factory)
    settings = ReportBriefSettings(api_key="test-key")
    runner = enrichment.SmallModelBriefRunner(settings)
    assert asyncio.run(runner.extract(build_context(request().documents[0], 16000))) == output()
    assert factory.call_args.kwargs["model"] == "gpt-5-mini"
    assert factory.call_args.kwargs["max_retries"] == 0
    assert factory.return_value.with_structured_output.call_args.kwargs["strict"] is True


def test_production_report_job_uses_enriched_fields_once_then_sends_result(monkeypatch):
    brief = validate()
    prepare = AsyncMock(return_value={2: brief})
    send = AsyncMock(
        return_value=SimpleNamespace(
            data=SimpleNamespace(report_id=1, status="COMPLETED", duplicate=False)
        )
    )
    monkeypatch.setattr(tasks, "prepare_report_briefs", prepare)
    monkeypatch.setattr(tasks, "ReportResultClient", lambda: SimpleNamespace(send=send))
    asyncio.run(tasks.run_report_job(uuid4(), request()))
    prepare.assert_awaited_once()
    result = send.call_args.args[0]
    assert result.status.value == "COMPLETED"
    assert f"• 제출·의견 기한: {DEADLINE}\n" in result.summary
    assert "[제4회 혁신대상 포상 신청서]" in result.summary


def test_table_header_is_not_an_applicant_even_with_real_quote():
    req = with_document(contentText="신청대상: 역할")
    assert (
        validate(output(applicants=[sourced("역할", quote="신청대상: 역할")]), req).facts.applicant
        == "원문 확인 필요"
    )


def test_expired_cache_calls_model_again():
    runner = SimpleNamespace(extract=AsyncMock(return_value=output()))
    run(runner=runner)
    key = next(iter(enrichment._CACHE))
    enrichment._CACHE[key] = (0, output())
    run(runner=runner)
    assert runner.extract.await_count == 2


def test_refused_structured_output_falls_back_without_automatic_retry(monkeypatch):
    factory = Mock()
    invoke = AsyncMock(return_value={"parsed": None, "parsing_error": None})
    factory.return_value.with_structured_output.return_value.ainvoke = invoke
    monkeypatch.setattr(enrichment, "ChatOpenAI", factory)
    result = run(settings=ReportBriefSettings(api_key="test-key"))
    assert result[2].note == "제출 안내 자동 정리 실패"
    invoke.assert_awaited_once()


@pytest.mark.parametrize(
    "value", ["~‘27. 1. 28.(목)16:00까지(한국표준시간)", "~2027. 1. 28.(목)16:00까지"]
)
def test_leading_tilde_deadline_is_not_a_damaged_range(value):
    req = with_document(contentText="접수마감: " + value)
    assert validate(output(deadlines=[sourced(value)]), req).facts.deadline == value


@pytest.mark.parametrize("value", ["2027. 1. 28. ~", "~ 월 일까지", "2027. 2. 30.까지"])
def test_broken_deadline_is_still_rejected(value):
    req = with_document(contentText="접수마감: " + value)
    assert validate(output(deadlines=[sourced(value)]), req).facts.deadline == "원문 확인 필요"


def test_empty_model_fields_never_resurrect_ungrounded_prior_analysis():
    req = with_document(
        contentText="사업을 안내합니다.",
        comparisonSummary={"eligibility": "모든 기업", "applicationDeadline": "2027-01-28"},
    )
    brief = validate(output(applicants=[], deadlines=[], destinations=[], contacts=[]), req)
    assert brief.facts.applicant == brief.facts.deadline == "원문 확인 필요"


def test_empty_revalidation_does_not_silently_reuse_saved_checklist():
    brief = validate(output(documents=[]), request(checklist=["납세증명서"]))
    assert brief.documents == []
    assert brief.note == "기존 체크리스트의 제출 근거 재확인 필요"


def test_long_zip_forms_do_not_crowd_out_short_notice_and_its_contacts():
    notice = (
        "접수마감: ~‘27. 1. 28.(목)16:00까지(한국표준시간)\n"
        "제출방법: 한국 기관은 K-PASS, 스페인 기관은 CDTI에 동시 접수\n"
        "문의처: 국제협력사업실 02-0000-0000\n"
    )
    req = with_document(
        attachments=[
            {
                "fileName": "제출서류 양식.zip",
                "downloadUrl": "https://example.go.kr/forms.zip",
                "extractedText": "".join(
                    f"[파일: 양식{i} 계획서.hwp]\n" + "사업계획 작성란\n" * 900 for i in range(39)
                ),
            },
            {
                "fileName": "국문 접수 안내문.pdf",
                "downloadUrl": "https://example.go.kr/notice.pdf",
                "extractedText": notice,
            },
        ]
    )
    context = build_context(req.documents[0], 16000)
    selected = next(p for p in context.sources.values() if p.name == "국문 접수 안내문.pdf")
    assert selected.text == notice.strip()
    assert sum(len(p.text) for p in context.sources.values()) <= 16000


def test_conditional_form_retains_requirement_and_real_zip_link():
    title = "외부기술도입비 현물산정 신청서"
    quote = f"제출서류: {title} (해당 시)"
    req = with_document(
        contentText=quote,
        checklist=["납세증명서"],
        attachments=[
            {
                "fileName": "제출서류 양식.zip",
                "downloadUrl": "https://example.go.kr/forms.zip",
                "extractedText": f"[파일: (양식10) {title}.hwp]\n기관명:",
            }
        ],
    )
    item = {
        "title": title,
        "condition": "해당 시",
        "evidence": {"source_id": "source-0", "quote": quote},
        "form_source_id": "source-1",
    }
    brief = validate(output(documents=[item]), req)
    assert brief.documents[0].title == title + " (해당 시)"
    assert brief.documents[0].form.url == "https://example.go.kr/forms.zip"
    assert "납세증명서" not in TemplateReportGenerator().generate(req, briefs={2: brief}).summary


def test_reference_guide_cannot_supply_another_programs_submission_address():
    req = with_document(
        attachments=[
            {
                "fileName": "참고자료.zip",
                "downloadUrl": "https://example.go.kr/reference.zip",
                "extractedText": "[파일: 해외사업 가이드북.pdf]\n제출처: other@example.org",
            }
        ]
    )
    brief = validate(output(destinations=[sourced("other@example.org", source_id="source-1")]), req)
    assert brief.facts.destination == "원문 확인 필요"
    assert submission_facts(req.documents[0]).destination == "원문 확인 필요"


def test_table_fragments_preserve_date_time_and_zone_without_other_columns():
    quote = "국내 일정\n접수 선정평가\n~‘27. 1. 28.(목)\n16:00까지 ‘27년 3월~4월\n(한국표준시간)"
    req = with_document(contentText=quote)
    value = sourced("표에서 읽은 마감일", quote)
    value["fragments"] = ["~‘27. 1. 28.(목)", "16:00까지", "(한국표준시간)"]
    assert validate(output(deadlines=[value]), req).facts.deadline == (
        "~‘27. 1. 28.(목) 16:00까지 (한국표준시간)"
    )
    value["fragments"] = ["~‘27. 1. 28.(목)", "18:00까지"]
    assert validate(output(deadlines=[value]), req).facts.deadline == "원문 확인 필요"


def test_typographic_quotes_do_not_invalidate_real_date_evidence():
    req = with_document(contentText="접수: ‘27. 1. 28. 16:00까지")
    value = sourced("'27. 1. 28. 16:00까지", "‘27. 1. 28. 16:00까지")
    assert validate(output(deadlines=[value]), req).facts.deadline == "'27. 1. 28. 16:00까지"


def test_applicant_table_header_is_not_a_qualification():
    req = with_document(contentText="국내주관기관자격 국내공동기관자격")
    assert (
        validate(output(applicants=[sourced("국내주관기관")]), req).facts.applicant
        == "원문 확인 필요"
    )


def test_model_receives_real_lines_and_reference_body_is_excluded(monkeypatch):
    factory = Mock()
    invoke = AsyncMock(return_value={"parsed": output()})
    factory.return_value.with_structured_output.return_value.ainvoke = invoke
    monkeypatch.setattr(enrichment, "ChatOpenAI", factory)
    req = with_document(
        contentText="접수마감: 2027-01-28\n제출처: 온라인",
        attachments=[
            {
                "fileName": "참고자료.pdf",
                "downloadUrl": "https://example.go.kr/ref",
                "extractedText": "제출처: other@example.org",
            }
        ],
    )
    runner = enrichment.SmallModelBriefRunner(ReportBriefSettings(api_key="test-key"))
    asyncio.run(runner.extract(build_context(req.documents[0], 16000)))
    prompt = invoke.call_args.args[0][1][1]
    assert "2027-01-28\n제출처" in prompt
    assert "other@example.org" not in prompt
    assert factory.call_args.kwargs["reasoning_effort"] == "minimal"


def test_verified_table_label_and_value_can_be_composed_without_new_words():
    quote = "국내주관기관자격 국내공동기관자격\n중소·중견 제한없음"
    req = with_document(contentText=quote)
    value = sourced("국내주관기관자격 중소·중견", quote)
    assert validate(output(applicants=[value]), req).facts.applicant == value["text"]
    value["text"] = "국내주관기관자격 대기업"
    assert validate(output(applicants=[value]), req).facts.applicant == "원문 확인 필요"


def test_budget_column_is_not_part_of_a_deadline():
    text = "~‘27. 1. 28.(목) 이내/년 16:00까지"
    req = with_document(contentText=text)
    assert validate(output(deadlines=[sourced(text)]), req).facts.deadline == "원문 확인 필요"


def test_verbatim_destination_survives_a_bad_surrounding_quote():
    text = "연구개발계획서 접수 (한국) www.k-pass.kr"
    req = with_document(contentText=text)
    value = sourced(text, "추진 절차 ... " + text)
    assert validate(output(destinations=[value]), req).facts.destination == text


def test_reference_checklist_cannot_become_current_required_documents():
    quote = "제출서류: 신청서"
    req = with_document(
        attachments=[
            {
                "fileName": "참고 가이드북.pdf",
                "downloadUrl": "https://example.go.kr/guide",
                "extractedText": quote,
            }
        ]
    )
    item = {
        "title": "신청서",
        "condition": None,
        "evidence": {"source_id": "source-1", "quote": quote},
        "form_source_id": None,
    }
    assert validate(output(documents=[item]), req).documents == []


def test_country_submission_rows_keep_both_routes_and_exclusion_condition():
    source = (
        "국내기관은 KIAT에, 해외기관은 해외 전문기관에 동시에 과제를 신청하여야\n"
        "접수가 인정되며, 한 쪽 기관에만 신청할 경우 사전 제외\n"
        "연구개발계획서 접수 (한국) 한국산업기술진흥원 www.k-pass.kr\n"
        "(스페인) CDTI 홈페이지\n양국 공통 접수된 과제만 평가"
    )
    req = with_document(contentText=source)
    brief = validate(output(destinations=[sourced("www.k-pass.kr")]), req)
    assert "www.k-pass.kr" in brief.facts.destination
    assert "(스페인) CDTI 홈페이지" in brief.facts.destination
    assert "한 쪽 기관에만 신청할 경우 사전 제외" in brief.facts.destination
    assert "평가" not in brief.facts.destination


def test_empty_model_document_list_keeps_explicit_submission_route_document():
    req = with_document(
        contentText="연구개발계획서 접수 (한국) www.k-pass.kr",
        attachments=[
            {
                "fileName": "양식.zip",
                "downloadUrl": "https://example.go.kr/forms.zip",
                "extractedText": "[파일: 국문연구개발계획서.hwp]\n기관명:",
            }
        ],
    )
    brief = validate(output(documents=[]), req)
    assert [d.title for d in brief.documents] == ["연구개발계획서"]
    assert brief.documents[0].form.url == "https://example.go.kr/forms.zip"
    assert brief.note == "전체 제출서류 목록은 원문 확인 필요"
