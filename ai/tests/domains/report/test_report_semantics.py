import pytest

from app.domains.report.semantics import (
    field_evidence_valid,
    source_contacts,
    source_submission_routes,
)
from tests.domains.report.test_report_template import with_document

ROUTES = (
    "7. 신청방법, 신청서 제출기한 및 접수처\n"
    "- 계획서(필수서류) 및 첨부서류 온라인 일괄 접수 -\n"
    "* 오프라인(방문) 서류제출 불필요\n"
    "□ 전산 등록처: 범부처통합연구지원시스템(IRIS, www.iris.go.kr)\n"
    "□ 재무제표 제출처: 원클릭서비스(m.one-click.co.kr/#/ketep)\n"
    "※ 원클릭서비스 최초 이용시 약관 동의 필요\n"
)
OBJECTION = (
    "4-4. 연구개발과제 중복성 제기\n"
    "o 제기처: (06175) 서울특별시 강남구 예시로 12 사업지원기관\n"
    "o 제기방법: 제기기관 대표자 명의의 공문 제출(관련 근거자료 첨부)\n"
)
CONTACTS = (
    "8. 문의처\n"
    "o 연구개발계획서 전산 등록 관련 문의: IRIS 고객센터(☎ 02-0000-0000)\n"
    "o 재무제표 온라인 제출 관련 문의: 예시평가정보(☎ 02-0000-0001)\n"
)


def document(source, *, title="2026년도 신규지원 대상 연구개발과제 공고", attachments=None):
    return with_document(
        title=title, contentText=source, attachments=attachments or [], comparisonSummary={},
    ).documents[0]


@pytest.mark.parametrize("quote,value", [
    (OBJECTION, "중복성 제기 접수처: 서울특별시 강남구 예시로 12"),
    ("o 제기방법: 제기기관 대표자 명의의 공문 제출(관련 근거자료 첨부)",
     "대표자 명의의 공문 제출"),
    ("o 제기처: (06175) 서울특별시 강남구 예시로 12 사업지원기관",
     "서울특별시 강남구 예시로 12"),
])
def test_business_objection_is_not_an_application_route(quote, value):
    assert not field_evidence_valid("destination", value, quote, document(OBJECTION + ROUTES))


@pytest.mark.parametrize(
    "value", ["온라인 일괄 접수", "www.iris.go.kr", "m.one-click.co.kr/#/ketep"],
)
def test_short_legacy_route_uses_its_own_context_in_complete_source(value):
    text = OBJECTION + ("다른 안내입니다.\n" * 130) + ROUTES + CONTACTS
    assert field_evidence_valid("destination", value, text, document(text))


@pytest.mark.parametrize("title,expected", [
    ("운영규정 일부개정안 행정예고", True),
    ("법률 시행령 입법예고", True),
    ("2026년 지원사업 신규지원 대상 공고", False),
])
def test_primary_public_comment_submission_is_distinguished_from_business_comment(title, expected):
    text = "의견 제출방법: 전자우편 opinion@example.org로 제출"
    assert field_evidence_valid("destination", text, text, document(text, title=title)) is expected


@pytest.mark.parametrize("text", [
    "이의신청 접수방법: objection@example.org로 공문 제출",
    "선정 후 제출방법: 협약시스템에 온라인 제출",
    "정산 서류 제출처: settlement@example.org",
])
def test_secondary_or_post_selection_procedures_are_rejected(text):
    assert not field_evidence_valid("destination", text, text, document(text))


def test_publication_authority_and_date_are_not_a_contact():
    text = "공고기관: 기후에너지환경부 (공고일 2026년 10월 12일)"
    assert not field_evidence_valid("contact", text, text, document(text))
    assert source_contacts(document(text)) is None


@pytest.mark.parametrize("text", [
    "문의처: 사업지원팀 02-0000-0000",
    "문의: 사업지원팀 support@example.org",
    "문의 담당부서: 연구지원과",
    "IRIS 고객센터: 1877-0000",
])
def test_explicit_contact_channels_or_responsible_departments_are_valid(text):
    assert field_evidence_valid("contact", text, text, document(text))
    assert source_contacts(document(text)) == text


def test_contact_channel_must_be_present_in_evidence():
    quote = "문의처: 사업지원팀 support@example.org"
    assert not field_evidence_valid("contact", "문의처: other@example.org", quote, document(quote))


def test_source_recovery_preserves_actual_routes_and_conditions_only():
    text = OBJECTION + ROUTES + CONTACTS
    result = source_submission_routes(document(text))
    assert result is not None
    assert "www.iris.go.kr" in result and "m.one-click.co.kr/#/ketep" in result
    assert "온라인 일괄 접수" in result and "오프라인(방문) 서류제출 불필요" in result
    assert "약관 동의 필요" in result
    assert "중복성" not in result and "대표자 명의" not in result
    assert "예시평가정보" not in result
    assert field_evidence_valid("destination", result, text, document(text))


def test_source_contacts_preserve_their_purpose_without_inventing_table_phone():
    text = CONTACTS + "담당부서\n연락처 (☎ 02-0000-0000)\n사업지원실\n8421\n"
    result = source_contacts(document(text))
    assert "전산 등록 관련 문의" in result and "재무제표 온라인 제출 관련 문의" in result
    assert "02-0000-8421" not in result


def test_reference_and_form_files_are_not_recovery_sources():
    attachments = [
        {"fileName": name, "downloadUrl": "https://example.org/file",
         "extractedText": ROUTES + CONTACTS}
        for name in ("신청서 양식.hwp", "참고자료.pdf")
    ]
    doc = document("공고기관: 예시부", attachments=attachments)
    assert source_submission_routes(doc) is None
    assert source_contacts(doc) is None


def test_recovery_deduplicates_identical_primary_sources():
    attachment = {"fileName": "공고문.pdf", "downloadUrl": "https://example.org/file",
                  "extractedText": ROUTES + CONTACTS}
    one = document(ROUTES + CONTACTS)
    duplicate = document(ROUTES + CONTACTS, attachments=[attachment])
    assert source_submission_routes(one) == source_submission_routes(duplicate)
    assert source_contacts(one) == source_contacts(duplicate)


def test_generic_institution_url_is_not_an_application_route():
    quote = "기관 홈페이지: https://example.org"
    assert not field_evidence_valid("destination", "https://example.org", quote, document(quote))


def test_unrelated_field_types_are_not_changed():
    assert field_evidence_valid("applicant", "기업", "기업", document("기업"))


@pytest.mark.parametrize("prefix", ["한국 접수", "해외 접수", "영국측 제출"])
def test_country_route_short_labels_are_valid(prefix):
    text = prefix + ": https://example.org/apply/path"
    assert field_evidence_valid("destination", text, text, document(text))
    assert source_submission_routes(document(text)) == text


def test_submission_support_contact_is_not_a_submission_route():
    text = "온라인 제출 관련 문의: 예시고객센터 02-0000-0001"
    assert not field_evidence_valid("destination", text, text, document(text))
    assert source_submission_routes(document(text)) is None


def test_same_line_other_fields_are_not_recovered_as_part_of_route():
    text = ("제출기한: 2026년 10월 14일 - 제출방법: 온라인 제출 후 원본 우편발송 "
            "※ 붙임 1. 신청서 1부. 2. 공적조서 1부.")
    assert source_submission_routes(document(text)) == "제출방법: 온라인 제출 후 원본 우편발송"


def test_recovery_does_not_merge_collapsed_pdf_copy_with_structured_hwpx():
    structured = ROUTES + CONTACTS
    flattened = structured.replace("\n", " ")
    attachments = [
        {"fileName": "사업 공고.hwpx", "downloadUrl": "https://example.org/hwpx",
         "extractedText": structured},
        {"fileName": "사업 공고.pdf", "downloadUrl": "https://example.org/pdf",
         "extractedText": flattened},
    ]
    doc = document("사업 공고입니다.", attachments=attachments)
    assert source_submission_routes(doc) == source_submission_routes(document(structured))
    assert source_contacts(doc) == source_contacts(document(structured))


def test_contact_section_routes_are_support_channels_not_application_destinations():
    text = ("1. 접수 관련 문의\n영문 연구개발계획서 접수: 지원사무국 help@example.org\n"
            "국문 연구개발계획서 접수: 시스템지원 02-0000-0000\n"
            "2. 사업 내용 관련 문의\n국제협력사업실 담당자 02-0000-0001")
    doc = document(text)
    contacts = source_contacts(doc)
    assert "help@example.org" in contacts and "02-0000-0001" in contacts
    assert source_submission_routes(doc) is None
    assert not field_evidence_valid(
        "destination", "help@example.org",
        "영문 연구개발계획서 접수: 지원사무국 help@example.org", doc,
    )


def test_contact_label_value_rows_preserve_numbers_without_combining_extensions():
    text = ("ㅇ 접수 및 문의처\n주소\n서울시 예시로 1\n지원기관 사업지원실\n"
            "연락처\n02-0000-0000/0001\n이메일\nhelp@example.org (우편 접수 후 이메일 제출)")
    contacts = source_contacts(document(text))
    assert "02-0000-0000/0001" in contacts and "help@example.org" in contacts
    assert "사업지원실" in contacts
    assert "02-0000-0001" not in contacts


def test_responsible_department_work_instructions_are_not_contact_information():
    text = "담당부서는 서류를 검토하여 운영지원과로 추천하고 승인 결과를 통보함"
    assert not field_evidence_valid("contact", text, text, document(text))
    assert source_contacts(document(text)) is None


def test_postal_submission_line_under_heading_keeps_receipt_conditions():
    text = ("□ 접수방법\nㅇ 제출서류는 우편(마감일 소인분까지 유효) 접수 후 이메일 제출\n"
            "* 방문접수는 마감일 18시까지 접수함\n"
            "* 제출서류의 전자파일은 한글파일 형태로 이메일 제출(서명본은 PDF도 제출)")
    route = source_submission_routes(document(text))
    assert "소인분까지 유효" in route and "마감일 18시까지" in route
    assert "서명본은 PDF도 제출" in route


@pytest.mark.parametrize("heading", ["4. 중복성 제기", "이의신청", "선정 후 제출 안내"])
def test_deadline_restores_source_procedure_heading(heading):
    quote = "접수기간: 2026년 10월 15일까지"
    doc = document(heading + "\n" + quote + "\n7. 신청방법\n신청마감: 2026년 11월 1일")
    assert not field_evidence_valid("deadline", "2026년 10월 15일까지", quote, doc)
    assert field_evidence_valid("deadline", "2026년 11월 1일", "신청마감: 2026년 11월 1일", doc)


def test_public_comment_deadline_is_valid_for_administrative_notice():
    quote = "의견제출 기한: 2026년 10월 15일까지"
    doc = document(quote, title="운영규칙 행정예고")
    assert field_evidence_valid("deadline", quote, quote, doc)


def test_separate_submission_label_and_email_are_recovered():
    source = "제출처\nsubmit@example.org"
    assert field_evidence_valid("destination", "submit@example.org", source, document(source))
    assert source_submission_routes(document(source)) == "제출처: submit@example.org"


@pytest.mark.parametrize("value", ["__________", "제출처: __________", "제출방법: ....."])
def test_empty_form_placeholders_are_not_submission_routes(value):
    assert not field_evidence_valid("destination", value, value, document(value))
    assert source_submission_routes(document(value)) is None


def test_online_navigation_heading_and_partial_steps_are_not_routes():
    text = ("신청방법: 온라인 접수 (방문 제출 받지 않음)\n"
            "온라인 접수처: https://example.org/apply\n[ 온라인 접수 절차 ]\n"
            "① 회원가입: 최초 가입 필수\n② 온라인 등록: 화면에 입력\n③ 파일 업로드: 저장")
    route = source_submission_routes(document(text))
    assert "https://example.org/apply" in route and "방문 제출 받지 않음" in route
    assert "절차" not in route and "①" not in route and "②" not in route


def test_shared_submission_contact_section_retains_postal_address_as_route():
    text = ("ㅇ 접수 및 문의처\n주소\n(06152) 서울시 예시구 예시로 1\n"
            "지원기관 사업지원실\n연락처\n02-0000-0000/0001\n"
            "이메일\nhelp@example.org (우편 접수 후 이메일 제출)")
    route = source_submission_routes(document(text))
    contacts = source_contacts(document(text))
    assert "주소: (06152) 서울시 예시구 예시로 1" in route
    assert "사업지원실" in route
    assert "서울시" not in contacts
    assert contacts.count("02-0000-0000/0001") == 1
    assert contacts.count("help@example.org") == 1


@pytest.mark.parametrize("heading", ["중복성 제기", "이의신청", "선정 후 협약 안내"])
def test_secondary_procedure_contact_is_rejected_even_with_real_phone(heading):
    quote = "문의처: 사업지원팀 02-0000-0000"
    doc = document(heading + "\n" + quote)
    assert not field_evidence_valid("contact", "사업지원팀 02-0000-0000", quote, doc)
    assert not field_evidence_valid("contact", heading + " 문의: 02-0000-0000", quote, doc)


def test_formatted_fallback_deadline_retains_source_section_purpose():
    source = ("4. 중복성 제기\n접수기간: ’26.10.15.까지\n"
              "7. 신청방법\n신청기한: 2026.11.01.까지")
    doc = document(source)
    bad = "접수기간: 2026년 10월 15일까지"
    good = "신청기한: 2026년 11월 1일까지"
    assert not field_evidence_valid("deadline", bad, bad, doc)
    assert field_evidence_valid("deadline", good, good, doc)


def test_ambiguous_shared_date_does_not_choose_application_over_objection():
    source = ("4. 중복성 제기\n접수기간: 2026.10.15.까지\n"
              "7. 신청방법\n신청기한: 2026.10.15.까지")
    formatted = "2026년 10월 15일까지"
    doc = document(source)
    assert not field_evidence_valid("deadline", formatted, formatted, doc)
    exact = "신청기한: 2026.10.15.까지"
    assert field_evidence_valid("deadline", exact, exact, doc)
