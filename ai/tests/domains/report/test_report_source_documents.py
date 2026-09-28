import asyncio
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.domains.report import enrichment
from app.domains.report.brief import build_context, fallback_brief, validate_brief
from app.domains.report.config import ReportBriefSettings
from app.domains.report.submission_documents import source_submission_documents
from app.domains.report.template import TemplateReportGenerator
from tests.domains.analysis.proposals.test_numbered_submission_tables import LEVEL_TABLE
from tests.domains.analysis.proposals.test_submission_requirements import FORMS, TABLE
from tests.domains.report.test_report_brief import output, run
from tests.domains.report.test_report_checklist_reuse import ready_request
from tests.domains.report.test_report_template import with_document

APPLICATION_NAMES = [
    "사업계획서",
    "결산재무제표",
    "표준 현장실습 학기제 운영계획서",
    "사업자 등록증",
    "기관소개 자료",
]
LINK = "접수방법\n- [붙임1] 신청서식을 작성하여 날인된 원본 스캔본 PDF를 이메일로 제출"
FORM = """이 사업의 지원을 신청합니다.
첨부서류 1. 사업계획서 1부
2. 확약서 1부
3. 사업자등록증 각1부
4. 직전회계연도 재무제표(포괄손익계산서 포함) 1부
5. 중소기업 확인서 또는 중견기업 확인서
6. 관련 증빙자료
20 년 월 일
신청업체 :
"""


@pytest.fixture(autouse=True)
def clear_cache():
    enrichment._CACHE.clear()
    yield
    enrichment._CACHE.clear()


def request(text=TABLE + FORMS, **kwargs):
    return with_document(contentText=text, **kwargs)


def names(brief):
    return [item.title for item in brief.documents]


def empty():
    return output(applicants=[], deadlines=[], destinations=[], contacts=[], documents=[])


def validate(req, model=None):
    doc = req.documents[0]
    return validate_brief(model or empty(), build_context(doc, 16000), doc)


def linked_request(body=LINK, text=FORM, filename="[붙임1] 사업 신청서식.hwp"):
    return request(
        body,
        attachments=[
            {
                "fileName": filename,
                "downloadUrl": "https://example.org/forms",
                "extractedText": text,
            }
        ],
    )


def test_empty_model_output_gets_only_verified_application_documents():
    result = validate(request())
    assert names(result) == APPLICATION_NAMES
    assert result.note == "원문 표에서 확인한 서류만 표시"
    assert not result.uses_saved_checklist
    assert result.documents[3].requirement_level == "CONDITIONAL"
    assert "최초 참여" in result.documents[3].condition
    assert "포함하는 자료" in result.documents[2].detail


def test_verified_rows_supplement_model_output_and_preserve_other_supported_rows():
    text = TABLE + "\n제출서류: 납세증명서\n"
    model = empty().model_copy(
        update={
            "documents": output(
                documents=[
                    {
                        "title": "납세증명서",
                        "condition": None,
                        "form_source_id": None,
                        "evidence": {"source_id": "source-0", "quote": "제출서류: 납세증명서"},
                    }
                ]
            ).documents
        }
    )
    assert names(validate(request(text), model)) == ["사업계획서", "결산재무제표", "납세증명서"]


def test_source_row_condition_wins_over_a_condition_borrowed_from_another_row():
    model = output(
        documents=[
            {
                "title": "사업계획서",
                "condition": "해당시",
                "form_source_id": None,
                "evidence": {"source_id": "source-0", "quote": LEVEL_TABLE},
            }
        ]
    )
    result = validate(request(LEVEL_TABLE), model)
    assert len(result.documents) == 4
    assert result.documents[0].condition == "주관기관"
    assert result.documents[0].requirement_level == "MANDATORY"


def test_saved_current_checklist_remains_authoritative():
    req = ready_request(["준비확인서"])
    req.documents[0].content_text = TABLE + FORMS
    assert names(validate(req)) == ["준비확인서"]
    assert validate(req).uses_saved_checklist


def test_old_unverified_checklist_does_not_override_source_on_failure():
    req = request(checklist=["근거 없는 준비확인서"])
    result = fallback_brief(req.documents[0], "제출 안내 자동 정리 실패")
    assert names(result) == APPLICATION_NAMES
    assert not result.uses_saved_checklist


@pytest.mark.parametrize("error", [TimeoutError(), ValueError("invalid model output")])
def test_model_failure_keeps_source_list_and_does_not_add_calls(error):
    req = request()
    runner = SimpleNamespace(extract=AsyncMock(side_effect=error))
    result = run(req, runner)[2]
    assert names(result) == APPLICATION_NAMES
    assert result.note == "제출 안내 자동 정리 실패"
    runner.extract.assert_awaited_once()


@pytest.mark.parametrize("total,per_document", [(0.01, 10), (10, 0.01)])
def test_real_timeout_cancellation_keeps_source_list(total, per_document):
    async def slow(context):
        await asyncio.sleep(1)
        return empty()

    req = request()
    runner = SimpleNamespace(extract=AsyncMock(side_effect=slow))
    settings = replace(
        ReportBriefSettings(), total_timeout_seconds=total, timeout_seconds=per_document
    )
    result = run(req, runner, settings)[2]
    assert names(result) == APPLICATION_NAMES
    assert result.note in {"제출 안내 자동 정리 실패", "제출 안내 자동 정리 시간 초과"}
    runner.extract.assert_awaited_once()


def test_disabled_model_still_renders_names_and_real_form_url():
    req = request(
        attachments=[
            {
                "fileName": "사업계획서.hwp",
                "downloadUrl": "https://example.org/form?a=1&b=2",
            }
        ]
    )
    runner = SimpleNamespace(extract=AsyncMock(return_value=empty()))
    result = run(req, runner, replace(ReportBriefSettings(), enabled=False))
    body = TemplateReportGenerator().generate(req, briefs=result).summary
    assert "[사업계획서](https://example.org/form?a=1&b=2)" in body
    assert "• 결산재무제표" in body
    assert "최초 참여" not in body and "원문 제출 안내" not in body
    assert "체크리스트 미작성" not in body
    runner.extract.assert_not_awaited()


def test_setup_failure_keeps_source_rows(monkeypatch):
    def unavailable():
        raise ValueError("unavailable")

    monkeypatch.setattr(enrichment.ReportBriefSettings, "from_env", unavailable)
    req = request()
    result = asyncio.run(enrichment.prepare_report_briefs(req))[2]
    assert names(result) == APPLICATION_NAMES


def test_model_excerpt_budget_does_not_limit_local_source_recovery():
    req = request()
    runner = SimpleNamespace(extract=AsyncMock(return_value=empty()))
    result = run(req, runner, replace(ReportBriefSettings(), max_text_chars=1))[2]
    assert names(result) == APPLICATION_NAMES


def test_cached_model_output_reuses_call_and_resolves_current_form_url():
    req = request(
        attachments=[{"fileName": "사업계획서.hwp", "downloadUrl": "https://example.org/old"}]
    )
    runner = SimpleNamespace(extract=AsyncMock(return_value=empty()))
    assert names(run(req, runner)[2]) == APPLICATION_NAMES
    req.documents[0].attachments[0].download_url = "https://example.org/new"
    result = run(req, runner)[2]
    assert result.documents[0].form.url == "https://example.org/new"
    runner.extract.assert_awaited_once()


@pytest.mark.parametrize("text", [TABLE, LEVEL_TABLE])
def test_reference_tables_cannot_supply_requirements(text):
    req = request(
        "예시 자료입니다.",
        attachments=[
            {
                "fileName": "참고자료.zip",
                "downloadUrl": "https://example.org/ref",
                "extractedText": "[파일: 공고문.hwp]\n" + text,
            }
        ],
    )
    assert validate(req).documents == []


def test_filename_or_unlinked_form_heading_does_not_prove_submission():
    req = request(
        "사업 소개입니다.",
        attachments=[
            {
                "fileName": "사업계획서.hwp",
                "downloadUrl": "https://example.org/form",
                "extractedText": "사업계획서\n기관명: 회사 이름\n사업명: 시험 사업",
            }
        ],
    )
    assert validate(req).documents == []


def test_table_heading_and_rows_cannot_cross_zip_member_boundary():
    text = "[파일: 공고문.hwp]\n□ 제출서류\n순번 제출서류 파일형태 필수여부 대상\n"
    text += "[파일: 양식.hwp]\n1 사업계획서 HWP 필수 주관기관"
    req = request(
        "첨부 참조",
        attachments=[
            {
                "fileName": "첨부.zip",
                "downloadUrl": "https://example.org/zip",
                "extractedText": text,
            }
        ],
    )
    assert validate(req).documents == []


@pytest.mark.parametrize(
    "text",
    [
        "□ 제출서류\n서류명 파일형식 비고\n사업계획서 hwp 제출\n재무제표 pdf 제출",
        "□ 제출서류\n순번 제출서류 파일형태 필수여부 대상\n"
        "1 사업계획서 hwp 필수 주관기관\n2 재무제표 pdf 필수 영리기관",
    ],
)
def test_truncated_final_row_is_not_confirmed_without_its_exceptions(text):
    text += "\n[일부 원문 생략: 파일 뒷부분 미전달]\n비영리 면제"
    assert names(validate(request(text))) == ["사업계획서"]


def test_closed_table_before_omission_keeps_its_last_complete_row():
    text = LEVEL_TABLE + "\n[일부 원문 생략: 파일 뒷부분 미전달]"
    assert len(validate(request(text)).documents) == 4


def test_explicitly_requested_application_form_recovers_named_enclosures():
    result = validate(linked_request())
    assert names(result) == [
        "사업계획서",
        "확약서",
        "사업자등록증",
        "직전회계연도 재무제표(포괄손익계산서 포함)",
        "중소기업 확인서 또는 중견기업 확인서",
    ]
    assert all(item.requirement_level == "MANDATORY" for item in result.documents)


@pytest.mark.parametrize(
    "case", ["unlinked", "wrong_id", "example", "later", "not_application", "duplicate"]
)
def test_enclosure_list_requires_an_unambiguous_application_reference(case):
    req = linked_request()
    doc = req.documents[0]
    if case == "unlinked":
        doc.content_text = "첨부파일을 참고하세요."
    elif case == "wrong_id":
        doc.attachments[0].file_name = "[붙임2] 사업 신청서식.hwp"
    elif case == "example":
        doc.attachments[0].extracted_text = "작성 예시\n" + FORM
    elif case == "later":
        doc.content_text = "선정 후 " + LINK
    elif case == "not_application":
        doc.attachments[0].extracted_text = FORM.replace("지원을 신청합니다.", "운영 결과입니다.")
    else:
        doc.attachments.append(doc.attachments[0].model_copy())
    assert source_submission_documents(doc) == []


def test_overflow_from_source_rows_points_to_original_not_nonexistent_proposal():
    text = (
        "□ 제출서류\n서류명 파일형식 비고\n"
        + "\n".join(f"서류{i}확인서\npdf\n제출" for i in range(8))
        + "\n문의처"
    )
    req = request(text)
    body = TemplateReportGenerator().generate(req, briefs={2: validate(req)}).summary
    assert "추가 제출서류 2종: 원문에서 확인" in body
    assert "사업 제안 체크리스트에서 확인" not in body


@pytest.mark.parametrize("text", [TABLE, LEVEL_TABLE])
def test_example_heading_in_body_cannot_supply_required_rows(text):
    assert validate(request("□ 참고용 예시 자료\n" + text)).documents == []


def test_form_continuation_cannot_hide_an_exemption():
    text = FORM.replace("2. 확약서 1부", "※ 사업계획서는 기존 참여기관 제출 면제\n2. 확약서 1부")
    assert source_submission_documents(linked_request(text=text).documents[0]) == []


def test_truncated_form_list_does_not_confirm_the_last_incomplete_row():
    text = "지원을 신청합니다.\n첨부서류 1. 사업계획서 1부\n2. 재무제표 1부\n"
    text += "[일부 원문 생략: 파일 뒷부분 미전달]"
    assert names(validate(linked_request(text=text))) == ["사업계획서"]


def test_partial_source_recovery_keeps_an_original_list_check_notice():
    req = linked_request()
    result = fallback_brief(req.documents[0], "제출 안내 자동 정리 실패")
    body = TemplateReportGenerator().generate(req, briefs={2: result}).summary
    assert "• 사업계획서" in body
    assert "전체 제출서류 목록" in body
    assert "원문에서 확인" in body


@pytest.mark.parametrize(
    "instruction",
    [
        "선정 후 제출",
        "협약 체결 시 제출",
        "필요시 제출",
        "제출하지 않습니다",
        "제출 불필요",
    ],
)
def test_form_reference_does_not_create_an_unconditional_application_obligation(instruction):
    req = linked_request(body="접수방법\n[붙임1] 신청서식을 " + instruction)
    assert source_submission_documents(req.documents[0]) == []


def test_source_recovery_does_not_link_a_form_from_a_reference_archive():
    req = request(TABLE, attachments=[{
        "fileName": "참고자료.zip", "downloadUrl": "https://example.org/reference",
        "extractedText": "[파일: 사업계획서.hwp]\n사업계획서\n기관명: 작성 예시",
    }])
    result = validate(req)
    assert names(result) == ["사업계획서", "결산재무제표"]
    assert all(item.form is None for item in result.documents)
