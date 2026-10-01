import pytest

from app.domains.report.brief import build_context, fallback_brief, validate_brief
from app.domains.report.submission_documents import (
    SourcePart,
    SubmissionDocument,
    _form,
    display_submission_documents,
    source_submission_documents,
)
from app.domains.report.submission_rows import submission_item_rows
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_brief import output
from tests.domains.report.test_report_template import with_document

TABLE = """제출 서류
구분
제출항목
설명
필수
성과보고서 1부.
탐구참여인력, 문제정의, AI 접근방법, 결과 해석, 한계 및 개선방안 등 (양식 준수)
필수
참가신청서 1부.
참가자 전원의 개인정보 기재 및 대표자 1인의 자필 또는 전자서명 필수
필수
웹 서비스 : 테스트 URL
앱 : APK 파일 또는 TestFlight 등 베타 버전 링크
PC 프로그램 : 실행파일
필수
데모 시연 영상
프로토타입 실행화면, 데모영상 등(2~5분 내외, 주요 기능 시연)
선택
코드 저장소(Github) 링크 또는 AI 모델 코드
AI 모델 구현 코드, 모델 설명 등
선택
학습 데이터셋 요약 또는 샘플 결과물(예시 결과물)
실제 사용된 AI 결과물, 실행 스크립트 등
※ 성과보고서 (필수, 한글·워드·PPT 또는 PDF 제출)
"""
FORMS = """2026년 AI 라이프 솔루션 챌린지 성과보고서(최대 2장 이내)
AI 기술명
(팀으로 참여하는 경우 대표자 1명만 제출)
아이디어명
기술요약
최종목표
2026년 AI 라이프 솔루션 챌린지 참가신청서
개발자
정 보
성명
소속
생년월일
"""


def request():
    return with_document(attachments=[
        {"fileName": "2026 AI 라이프 솔루션 챌린지 공고문." + extension,
         "downloadUrl": "https://example.org/notice?format=" + extension,
         "extractedText": TABLE + FORMS}
        for extension in ("pdf", "hwpx")
    ])


def empty():
    return output(applicants=[], deadlines=[], destinations=[], contacts=[], documents=[])


@pytest.mark.parametrize("title", ["참가신청서 1부.", "성과보고서 1부."])
def test_quantity_and_embedded_form_resolve_editable_notice(title):
    doc = request().documents[0]
    parts = [SourcePart(a.file_name, a.extracted_text, a.download_url) for a in doc.attachments]
    assert _form(title, parts).url.endswith("hwpx")
    assert _form(title, list(reversed(parts))).url.endswith("hwpx")


@pytest.mark.parametrize("name,text", [
    ("공고문.hwpx", TABLE),
    ("참고 공고문.hwpx", TABLE + FORMS),
    ("신청서식.hwpx", "제출서류\n참가신청서\n성명: ____"),
    ("공고문.hwpx", "참가신청서 1부.\n성명: ____"),
])
def test_checklist_mentions_and_reference_files_are_not_form_links(name, text):
    assert _form("참가신청서 1부.", [SourcePart(name, text, "https://example.org/a")]) is None


def test_different_bundles_and_duplicate_editions_remain_ambiguous():
    for names in (("A 공고문.hwpx", "B 공고문.pdf"), ("공고문.hwpx", "공고문.hwpx")):
        assert _form("참가신청서", [
            SourcePart(name, FORMS, f"https://example.org/{i}") for i, name in enumerate(names)
        ]) is None


def test_table_preserves_four_required_deliverables_and_two_optional_items():
    rows = submission_item_rows(TABLE + FORMS)
    assert len(rows) == 6
    assert [r.level for r in rows] == ["MANDATORY"] * 4 + ["OPTIONAL"] * 2
    assert rows[2].title == (
        "웹 서비스 : 테스트 URL · 앱 : APK 파일 또는 TestFlight 등 베타 버전 링크 · "
        "PC 프로그램 : 실행파일"
    )
    assert rows[3].title == "데모 시연 영상"
    assert all(r.quote in TABLE for r in rows)


@pytest.mark.parametrize("prefix", ["□ 선정 후 준비사항\n", "□ 협약 시 제출\n", "□ 참고자료\n"])
def test_non_application_table_is_not_recovered(prefix):
    assert submission_item_rows(prefix + TABLE) == []


def test_incomplete_last_row_is_not_promoted_to_requirement():
    assert submission_item_rows("제출 서류\n구분 제출항목 설명\n필수\n참가신청서 1부.\n"
                                "서명 필수\n[일부 원문 생략]") == []


def test_pdf_centered_marker_keeps_service_options_in_one_item():
    text = """제출 서류
구분 제출항목 설명
필수 성과보고서 1부. 탐구 방법 및 결과 설명
필수 참가신청서 1부. 참가자 전원의 서명 필수
웹 서비스 : 테스트 URL
필수 앱 : APK 파일 또는 TestFlight 등 베타 버전 링크
PC 프로그램 : 실행파일
필수 데모 시연 영상 프로토타입 실행화면 및 주요 기능 시연
※ 양식 준수
"""
    rows = submission_item_rows(text)
    assert len(rows) == 4
    assert rows[2].title.startswith("웹 서비스 : 테스트 URL · 앱 : APK")
    assert "PC 프로그램 : 실행파일" in rows[2].title
    assert rows[2].quote in text


@pytest.mark.parametrize("mode", ["model", "fallback", "template"])
def test_report_recovers_omitted_items_and_links_without_duplicate_editions(mode):
    req = request()
    doc = req.documents[0]
    if mode == "model":
        brief = validate_brief(empty(), build_context(doc, 16000), doc)
    elif mode == "fallback":
        brief = fallback_brief(doc, "테스트")
    else:
        brief = None
    if brief:
        assert len(brief.documents) == 6
    body = TemplateReportGenerator().generate(
        req, briefs={doc.version_id: brief} if brief else None
    ).summary
    assert body.count("[참가신청서 1부.](https://example.org/notice?format=hwpx)") == 1
    assert body.count("[성과보고서 1부.](https://example.org/notice?format=hwpx)") == 1
    assert body.count("• 데모 시연 영상") == 1
    assert "PC 프로그램 : 실행파일" in body
    assert body.count("(선택 제출)") == 2
    assert "format=pdf" not in body


def test_reference_archive_does_not_supply_report_requirements():
    req = with_document(attachments=[{
        "fileName": "참고자료.zip", "downloadUrl": "https://example.org/reference",
        "extractedText": "[파일: 공고문.hwpx]\n" + TABLE + FORMS,
    }])
    assert source_submission_documents(req.documents[0]) == []


def test_hwpx_duplicate_flattened_table_does_not_hide_optional_sample_deliverables():
    table = TABLE[TABLE.index("구분"):TABLE.index("※")]
    flattened = "".join(table.splitlines()) + "\n"
    text = TABLE.replace("구분\n", flattened + "구분\n", 1)
    assert submission_item_rows(text + FORMS) == submission_item_rows(TABLE + FORMS)


def test_explicit_standalone_form_wins_over_notice_bundle():
    assert _form("참가신청서 1부.", [
        SourcePart("공고문.hwpx", FORMS, "https://example.org/notice"),
        SourcePart("참가신청서.hwpx", "", "https://example.org/form"),
    ]).url == "https://example.org/form"


def test_numbered_different_editions_are_not_assumed_to_be_the_same_form():
    assert _form("참가신청서", [
        SourcePart("1.공고문.hwpx", FORMS, "https://example.org/a"),
        SourcePart("2.공고문.pdf", FORMS, "https://example.org/b"),
    ]) is None


def test_conflicting_required_and_optional_rows_are_not_collapsed():
    rows = [SubmissionDocument("추가 자료", None, level) for level in ("MANDATORY", "OPTIONAL")]
    assert display_submission_documents(rows) == rows
