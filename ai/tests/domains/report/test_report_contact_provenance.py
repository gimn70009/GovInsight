from dataclasses import replace

import pytest

from app.domains.report.brief import build_context, fallback_brief, validate_brief
from app.domains.report.semantics import field_evidence_valid, source_contacts
from tests.domains.report.test_report_brief import output, sourced
from tests.domains.report.test_report_template import with_document

REAL = "문의처: 사업지원실 053-000-8383, support@example.org"
FORM = ("붙임 1. 참가신청서\n제안자\n성명\n홍길동\n휴대전화\n010-1111-2222\n"
        "이메일\napplicant@example.org\n작성예시\n문의처: 신청자 010-3333-4444")


def document(content, attachments=None):
    return with_document(contentText=content, attachments=attachments or [],
                         comparisonSummary={}).documents[0]


def test_embedded_form_cannot_inherit_the_preceding_inquiry_section():
    doc = document("11. 문의처\n" + REAL + "\n" + FORM)
    contacts = source_contacts(doc)
    assert "053-000-8383" in contacts
    assert "010-1111-2222" not in contacts and "010-3333-4444" not in contacts
    assert "applicant@example.org" not in contacts
    for value in ("010-1111-2222", "010-3333-4444", "applicant@example.org"):
        assert not field_evidence_valid("contact", value, value, doc)
    assert not field_evidence_valid("contact", REAL + " 010-1111-2222", doc.content_text, doc)


@pytest.mark.parametrize("filename", ["참가신청서.hwp", "공고 및 참가신청서.hwp"])
def test_model_contact_quote_cannot_bypass_form_or_applicant_context(filename):
    attachment = {"fileName": filename, "downloadUrl": "https://example.org/form",
                  "extractedText": FORM}
    doc = document(REAL, [attachment])
    context = build_context(doc, 16000)
    candidate = sourced("010-1111-2222", source_id="source-1")
    brief = validate_brief(output(contacts=[candidate]), context, doc)
    assert "010-1111-2222" not in brief.facts.contact
    assert "053-000-8383" in brief.facts.contact
    assert "contact:wrong_purpose" in brief.validation_issues
    assert "010-1111-2222" not in fallback_brief(doc, "").facts.contact


def test_independent_real_inquiry_section_in_a_form_is_preserved():
    source = FORM + "\n3. 문의 안내\n문의처: 사업지원팀 010-1111-2222, help@example.org"
    doc = document("신청서 양식을 참고하세요.", [{
        "fileName": "참가신청서.hwp", "downloadUrl": "https://example.org/form",
        "extractedText": source,
    }])
    recovered = source_contacts(doc)
    assert "사업지원팀 010-1111-2222" in recovered
    assert "010-3333-4444" not in recovered and "applicant@example.org" not in recovered
    context = build_context(doc, 16000)
    quote = "문의처: 사업지원팀 010-1111-2222, help@example.org"
    brief = validate_brief(output(contacts=[sourced(quote, source_id="source-1")]), context, doc)
    assert "사업지원팀 010-1111-2222" in brief.facts.contact
    assert not any(issue.startswith("contact:") for issue in brief.validation_issues)


def test_selected_excerpt_restores_full_source_example_context_before_validation():
    doc = document("작성예시\n담당자: 예시지원실 02-0000-0000")
    context = build_context(doc, 16000)
    # Simulate bounded source selection omitting the heading; the full document
    # remains available to validate the role of this occurrence.
    source = context.sources["source-0"]
    context.sources["source-0"] = replace(source, text="담당자: 예시지원실 02-0000-0000")
    brief = validate_brief(output(contacts=[sourced("예시지원실 02-0000-0000")]), context, doc)
    assert "02-0000-0000" not in brief.facts.contact
    assert "contact:wrong_purpose" in brief.validation_issues


def test_contact_number_shape_is_never_used_as_a_blacklist():
    doc = document("문의처: 운영지원실 010-1111-2222, 010-3333-4444, help@example.org")
    assert "010-1111-2222" in source_contacts(doc)
    assert "010-3333-4444" in source_contacts(doc)
    assert field_evidence_valid("contact", doc.content_text, doc.content_text, doc)


def test_glued_email_columns_cannot_be_recovered_as_one_address():
    doc = document("문의처: 지원실 first@example.orgsecond@example.net")
    assert source_contacts(doc) is None
    assert not field_evidence_valid("contact", doc.content_text, doc.content_text, doc)


TABLE = (
    "11. 문의처 (공모전 문의에 해당하며 접수는 별도 안내 확인)\n"
    "구 분 문의처\n공모전 관련 문의 예시평가원 대외협력실\n예시학회 사무국\n"
    "전화 053-000-8383\n02-000-2310\n"
    "이메일 evaluation@example.org\nsociety@example.org\n공지사항 각 기관 홈페이지"
)


def test_explicit_contact_table_preserves_each_agencys_phone_and_email():
    doc = document(TABLE + "\n" + FORM)
    expected = ("예시평가원 대외협력실: 053-000-8383, evaluation@example.org\n"
                "예시학회 사무국: 02-000-2310, society@example.org")
    assert source_contacts(doc) == expected
    assert fallback_brief(doc, "").facts.contact == expected
    # A model may join every channel under the first agency. Source table
    # ownership must win even after values have passed verbatim-fragment checks.
    context = build_context(doc, 16000)
    candidate = sourced("예시평가원 대외협력실 053-000-8383 02-000-2310", quote=TABLE)
    candidate["fragments"] = ["예시평가원 대외협력실", "053-000-8383", "02-000-2310"]
    brief = validate_brief(output(contacts=[candidate]), context, doc)
    assert brief.facts.contact == expected
    reused = replace(context, reused_facts={"contact": candidate["text"]})
    assert validate_brief(output(contacts=[]), reused, doc).facts.contact == expected


def test_unbalanced_contact_table_does_not_invent_agency_ownership():
    doc = document(TABLE.replace("02-000-2310\n", ""))
    assert "예시학회 사무국: 053-000-8383" not in (source_contacts(doc) or "")
    assert "예시평가원 대외협력실: 053-000-8383" not in (source_contacts(doc) or "")


def test_personal_number_elsewhere_in_notice_is_not_inquiry_evidence():
    source = "공모전 수상자 이예시 010-1234-5678\n" + REAL
    doc = document(source)
    assert not field_evidence_valid("contact", "010-1234-5678", "010-1234-5678", doc)
    assert "010-1234-5678" not in (source_contacts(doc) or "")
