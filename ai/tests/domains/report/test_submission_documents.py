from types import SimpleNamespace

from app.domains.analysis.schemas.result import PreparationChecklistItem, RequirementSource
from app.domains.report.submission_documents import submission_documents
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_template import with_document


def test_required_document_names_and_form_links_without_owner_metadata():
    request = with_document(
        contentText="주관기관 제출서류: 사업계획서, 납세증명서\n문의처: 지원팀 02-0000-0000",
        attachments=[
            {"fileName": "공고문.pdf", "downloadUrl": "https://example.go.kr/notice.pdf"},
            {"fileName": "운영지침.pdf", "downloadUrl": "https://example.go.kr/rules.pdf"},
            {"fileName": "사업계획서 양식.hwp", "downloadUrl": "https://example.go.kr/form"},
        ],
    )
    items = submission_documents(request.documents[0])
    assert [item.title for item in items] == ["사업계획서", "납세증명서"]
    assert all(item.submitter == "주관기관" for item in items)
    assert items[0].form.url == "https://example.go.kr/form"
    assert items[1].form is None
    body = TemplateReportGenerator().generate(request).summary
    assert "준비 담당" not in body and "◆ " not in body
    assert "• [사업계획서](https://example.go.kr/form)" in body
    assert "• 납세증명서\n" in body
    assert "notice.pdf" not in body and "rules.pdf" not in body


def test_zip_inner_filename_maps_to_original_archive_not_invented_member_url():
    request = with_document(
        contentText="주관기관 제출서류: 사업계획서, 개인정보동의서",
        attachments=[
            {
                "fileName": "제출양식.zip",
                "downloadUrl": "https://example.go.kr/download?id=1&seq=2",
                "extractedText": "[파일: 양식/사업계획서.hwp]\n계획서 작성 서식\n\n"
                "[파일: 양식/개인정보동의서.hwp]\n개인정보 동의 서식",
            }
        ],
    )
    body = TemplateReportGenerator().generate(request).summary
    assert body.count("(ZIP)](https://example.go.kr/download?id=1&seq=2)") == 2
    assert "[사업계획서 (ZIP)]" in body
    assert "[개인정보동의서 (ZIP)]" in body
    assert "https://example.go.kr/download/" not in body


def test_filenames_and_archive_inventory_alone_do_not_prove_mandatory_submission():
    request = with_document(
        attachments=[
            {
                "fileName": "제출양식.zip",
                "downloadUrl": "https://example.go.kr/a.zip",
                "extractedText": "[파일: 신청서.hwp]\n기관명: ______\n성명: ______",
            }
        ]
    )
    assert submission_documents(request.documents[0]) == []
    body = TemplateReportGenerator().generate(request).summary
    assert "확인된 제출 서류가 없습니다" in body
    assert "a.zip" not in body


def test_conditional_and_post_selection_documents_are_not_universal_requirements():
    request = with_document(
        contentText=(
            "주관기관 제출서류: 사업계획서, 중소기업확인서(중소기업 해당 시)\n"
            "선정 후 제출서류: 협약신청서\n참고용 제출서류: 작성예시 신청서"
        )
    )
    items = submission_documents(request.documents[0])
    assert [(i.requirement, i.title) for i in items] == [
        ("필수", "사업계획서"),
        ("조건부", "중소기업확인서(중소기업 해당 시)"),
    ]


def test_same_document_for_different_roles_keeps_both_obligations():
    items = submission_documents(
        with_document(
            contentText=("주관기관 제출서류: 사업계획서\n공동기관 제출서류: 사업계획서")
        ).documents[0]
    )
    assert [i.submitter for i in items] == ["주관기관", "공동기관"]


def test_ambiguous_forms_and_inquiry_contact_do_not_become_selected_form_or_assignee():
    request = with_document(
        contentText="제출서류: 신청서\n문의처: 지원팀 02-0000-0000",
        attachments=[
            {"fileName": "신청서A.hwp", "downloadUrl": "https://example.go.kr/a"},
            {"fileName": "신청서B.hwp", "downloadUrl": "https://example.go.kr/b"},
        ],
    )
    item = submission_documents(request.documents[0])[0]
    assert item.form is None
    assert item.submitter == "제출 주체 확인 필요"


def prepared_document(text, *, quote, attachment_name="공고문.pdf", origin="ATTACHMENT"):
    doc = with_document(
        attachments=[
            {
                "fileName": "공고문.pdf",
                "downloadUrl": "https://example.go.kr/notice",
                "extractedText": text,
            }
        ]
    ).documents[0]
    item = PreparationChecklistItem(
        title="신청서",
        detail="신청 단계에 제출하는 신청서입니다.",
        next_action="신청서를 준비합니다.",
        requirement_level="MANDATORY",
        stage="APPLICATION",
        applies_to="주관기관",
        source=RequirementSource(
            origin=origin, attachment_name=attachment_name, section_title="제출서류", excerpt=quote
        ),
    )
    return doc.model_copy(
        update={
            "proposal": SimpleNamespace(preparation=SimpleNamespace(submission_documents=[item]))
        }
    )


def test_preparation_requires_exact_source_and_does_not_link_notice_as_form():
    quote = "주관기관은 신청서를 제출해야 합니다."
    items = submission_documents(prepared_document(quote, quote=quote))
    assert len(items) == 1 and items[0].submitter == "주관기관"
    assert items[0].form is None
    assert submission_documents(prepared_document("사업의 목적을 설명합니다.", quote=quote)) == []
    assert (
        submission_documents(prepared_document(quote, quote=quote, attachment_name="다른파일.pdf"))
        == []
    )


def test_missing_or_unsafe_form_is_not_fabricated():
    request = with_document(
        contentText="제출서류: 신청서",
        attachments=[
            {"fileName": "신청서.hwp", "downloadUrl": "https://user:secret@example.go.kr/form"}
        ],
    )
    body = TemplateReportGenerator().generate(request).summary
    assert "secret" not in body and "• 신청서\n" in body


def test_either_or_documents_remain_a_conditional_group():
    items = submission_documents(
        with_document(contentText="제출서류: 납세증명서 또는 납부확인서 중 택1").documents[0]
    )
    assert len(items) == 1 and items[0].requirement == "조건부"
    assert "택1" in items[0].title


def test_optional_document_does_not_hide_required_document_in_same_list():
    items = submission_documents(
        with_document(
            contentText="제출서류: 사업계획서, 실적증명서(선택), 납세증명서(해당 시)"
        ).documents[0]
    )
    assert [i.title for i in items] == ["사업계획서", "납세증명서(해당 시)"]


def test_flattened_notice_does_not_attach_inquiry_text_to_document_title():
    items = submission_documents(
        with_document(
            contentText="신청대상: 주관기관 제출서류: 사업계획서, 신청서 "
            "문의처: 지원팀 02-0000-0000"
        ).documents[0]
    )
    assert [i.title for i in items] == ["사업계획서", "신청서"]
    assert all(i.submitter == "주관기관" for i in items)


def test_flattened_numbered_table_extracts_names_not_headers_or_form_indices():
    request = with_document(
        contentText=(
            "제출서류: 공동 및 지원 부문별 제출서류 서식을 참고해 제출 요청 "
            "< 공동 제출서류 목록 > 제 출 서 류 증빙 서식 "
            "1) 신청서(개인, 단체, 공로) 1부 1-1(기업) / "
            "2-3(공로) 3) 정부포상 및 장관표창에 대한 동의서 1부 3-1 "
            "4) 개인정보 수집 및 이용 동의서 1부 3-2 "
            "5) 추천제한 여부 확인서 및 추천명단 1부 4-1(기업, 개인) / "
            "4-2(기업, 개인) 4-3(공로) 6) 소속기업·기관 사업자등록증 및 "
            "최근 5년 재무제표 1부 단체(기업)"
        )
    )
    titles = [i.title for i in submission_documents(request.documents[0])]
    assert "신청서(개인, 단체, 공로)" in titles
    assert "정부포상 및 장관표창에 대한 동의서" in titles
    assert "개인정보 수집 및 이용 동의서" in titles
    assert "소속기업·기관 사업자등록증 및 최근 5년 재무제표" in titles
    assert all(
        not any(v in title for v in ["증빙 서식", "제출 요청", "1부", "3-2", "4-2"])
        for title in titles
    )


def test_instruction_that_mentions_document_is_not_a_document_name():
    doc = with_document(contentText="제출서류: 사업계획서를 작성하여 담당자에게 제출해야 합니다.")
    assert submission_documents(doc.documents[0]) == []


def test_form_named_notice_is_not_linked_as_application_form():
    doc = with_document(
        contentText="제출서류: 신청서",
        attachments=[
            {"fileName": "신청서 접수 공고문.pdf", "downloadUrl": "https://example.go.kr/notice"}
        ],
    )
    assert submission_documents(doc.documents[0])[0].form is None


def test_demand_survey_form_is_a_submission_document():
    request = with_document(
        contentText="제출서류: 수요조사서",
        attachments=[
            {"fileName": "수요조사서 양식.hwp", "downloadUrl": "https://example.go.kr/survey"}
        ],
    )
    assert (
        "[수요조사서](https://example.go.kr/survey)"
        in TemplateReportGenerator().generate(request).summary
    )
