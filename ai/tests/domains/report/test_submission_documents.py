from app.domains.report.submission_documents import submission_documents
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_template import with_document


def test_saved_checklist_is_the_only_source_even_if_report_excerpt_disagrees():
    request = with_document(
        checklist=["국문 연구개발계획서", "사업자등록증 사본", "최근 3년 재무제표"],
        contentText="제출서류: 오래된 신청서, 관계없는 공문",
    )
    assert [i.title for i in submission_documents(request.documents[0])] == [
        "국문 연구개발계획서",
        "사업자등록증 사본",
        "최근 3년 재무제표",
    ]
    body = TemplateReportGenerator().generate(request).summary
    assert "오래된 신청서" not in body and "관계없는 공문" not in body


def test_checklist_items_survive_truncated_or_missing_source_text():
    item = {
        "title": "국문 연구개발계획서",
        "source": {
            "origin": "ATTACHMENT",
            "attachmentName": "공고문.pdf",
            "sectionTitle": "제출 서류",
            "excerpt": "제출서류에는 국문 연구개발계획서가 필요합니다.",
        },
    }
    request = with_document(
        checklist=[item],
        attachments=[
            {
                "fileName": "공고문.pdf",
                "downloadUrl": "https://example.go.kr/notice",
                "extractedText": "보고서에 전달되는 앞부분에는 사업 목적만 있습니다.",
            }
        ],
    )
    items = submission_documents(request.documents[0])
    assert [i.title for i in items] == ["국문 연구개발계획서"]
    assert items[0].form is None


def test_company_prepared_items_and_all_application_levels_match_proposal_screen():
    request = with_document(
        checklist=[
            {"title": "회사 소개 자료", "requirementLevel": "RECOMMENDED"},
            {"title": "사업자등록증 사본", "requirementLevel": "MANDATORY"},
            {"title": "공동기관 확인서(해당 시)", "requirementLevel": "CONDITIONAL"},
            {"title": "추가 실적 자료", "requirementLevel": "OPTIONAL"},
            {"title": "협약서", "stage": "AGREEMENT"},
        ]
    )
    items = submission_documents(request.documents[0])
    assert [i.title for i in items] == [
        "회사 소개 자료",
        "사업자등록증 사본",
        "공동기관 확인서(해당 시)",
        "추가 실적 자료",
    ]
    assert all(i.form is None for i in items)


def test_report_does_not_split_or_rewrite_checklist_document_names():
    title = "기술거래기관·사업화전문회사 지정신청서 및 정관 사본"
    items = submission_documents(with_document(checklist=[title]).documents[0])
    assert [i.title for i in items] == [title]


def test_no_checklist_does_not_become_an_invented_raw_source_list():
    request = with_document(
        contentText="제출서류: 신청서",
        attachments=[{"fileName": "신청서.hwp", "downloadUrl": "https://example.go.kr/form"}],
    )
    assert submission_documents(request.documents[0]) == []
    body = TemplateReportGenerator().generate(request).summary
    assert "제출 준비 서류 ↓" not in body
    assert "안내:" in body and "체크리스트 미작성" not in body
    assert "https://example.go.kr/form" not in body


def test_form_links_and_company_documents_use_the_same_saved_checklist():
    request = with_document(
        checklist=["국문 연구개발계획서", "사업자등록증 사본"],
        attachments=[
            {
                "fileName": "붙임1. 제출서류 양식.zip",
                "downloadUrl": "https://example.go.kr/download?id=1&seq=2",
                "extractedText": "[파일: 국문 연구개발계획서.hwp]\n사업명: ____",
            },
            {"fileName": "공고문.pdf", "downloadUrl": "https://example.go.kr/notice"},
        ],
    )
    body = TemplateReportGenerator().generate(request).summary
    assert "[국문 연구개발계획서 (ZIP)](https://example.go.kr/download?id=1&seq=2)" in body
    assert "• 사업자등록증 사본" in body
    assert "notice)" not in body and "준비 담당" not in body


def test_unsafe_download_link_is_never_used_for_checklist_item():
    request = with_document(
        checklist=["신청서"],
        attachments=[
            {"fileName": "신청서.hwp", "downloadUrl": "https://user:secret@example.go.kr/form"}
        ],
    )
    body = TemplateReportGenerator().generate(request).summary
    assert "secret" not in body
    assert "• 신청서" in body


def test_reordered_filename_links_saved_designation_application():
    request = with_document(
        checklist=["기술거래기관·사업화전문회사 지정신청서", "정관 사본"],
        attachments=[
            {
                "fileName": "1. 2026년 기술거래기관 및 사업화전문회사 지정 신청 공고문.hwpx",
                "downloadUrl": "https://example.go.kr/notice",
            },
            {
                "fileName": "2. 신청서식(2026년 기술거래기관 및 사업화전문회사 지정).hwpx",
                "downloadUrl": "https://example.go.kr/form?id=12&seq=2",
            },
        ],
    )
    items = submission_documents(request.documents[0])
    assert items[0].form.url == "https://example.go.kr/form?id=12&seq=2"
    assert items[1].form is None


def test_explanatory_parenthesis_does_not_prevent_correct_form_link():
    request = with_document(
        checklist=["규제자유특구 지정 신청서(특구계획안 등 첨부)"],
        contentText="제출서류: 규제자유특구 지정 신청서(특구계획안 등 첨부)",
        attachments=[
            {
                "fileName": "규제자유특구 지정 신청서.hwp",
                "downloadUrl": "https://example.go.kr/designation",
            },
            {
                "fileName": "규제 신속확인 신청서.hwp",
                "downloadUrl": "https://example.go.kr/regulation",
            },
        ],
    )
    body = TemplateReportGenerator().generate(request).summary
    assert (
        "[규제자유특구 지정 신청서(특구계획안 등 첨부)](https://example.go.kr/designation)" in body
    )
    assert "regulation)" not in body


def test_designation_form_is_not_replaced_by_different_regulation_application():
    request = with_document(
        checklist=["규제자유특구 지정 신청서(특구계획안 등 첨부)"],
        contentText="제출서류: 규제자유특구 지정 신청서(특구계획안 등 첨부)",
        attachments=[
            {
                "fileName": "규제 신속확인 신청서.hwp",
                "downloadUrl": "https://example.go.kr/regulation",
            },
            {
                "fileName": "규제자유특구 지정 신청 공고문.hwpx",
                "downloadUrl": "https://example.go.kr/notice",
            },
        ],
    )
    assert submission_documents(request.documents[0])[0].form is None


def test_plan_in_application_form_bundle_requires_heading_and_form_fields():
    request = with_document(
        checklist=["사업계획서 (기술거래기관 및 사업화전문회사 계획서 별도 제출)"],
        contentText="제출서류: 사업계획서 (기술거래기관 및 사업화전문회사 계획서 별도 제출)",
        attachments=[
            {
                "fileName": "신청서식(기술거래기관 및 사업화전문회사).hwpx",
                "downloadUrl": "https://example.go.kr/bundle",
                "extractedText": "[별지 제2호] 사업계획서\n1. 사업개요\n사업명: ____\n2. 추진계획",
            },
        ],
    )
    assert submission_documents(request.documents[0])[0].form.url == "https://example.go.kr/bundle"


def test_application_bundle_checklist_mention_is_not_a_form_heading():
    request = with_document(
        checklist=["사업계획서"],
        contentText="제출서류: 사업계획서",
        attachments=[
            {
                "fileName": "신청서식.hwpx",
                "downloadUrl": "https://example.go.kr/bundle",
                "extractedText": "제출서류: 사업계획서\n기관명: ____",
            },
        ],
    )
    assert submission_documents(request.documents[0])[0].form is None


def test_reordered_forms_with_different_applicant_qualifiers_remain_distinct():
    request = with_document(
        checklist=["지정신청서(개인)"],
        contentText="제출서류: 지정신청서(개인)",
        attachments=[
            {"fileName": "신청서식(단체 지정).hwp", "downloadUrl": "https://example.go.kr/group"},
            {"fileName": "신청서식(개인 지정).hwp", "downloadUrl": "https://example.go.kr/person"},
        ],
    )
    assert submission_documents(request.documents[0])[0].form.url == "https://example.go.kr/person"


def test_reordered_form_with_multiple_candidates_is_not_arbitrarily_linked():
    request = with_document(
        checklist=["기술거래기관 지정신청서"],
        contentText="제출서류: 기술거래기관 지정신청서",
        attachments=[
            {
                "fileName": "신청서식(기술거래기관 지정 A).hwp",
                "downloadUrl": "https://example.go.kr/a",
            },
            {
                "fileName": "신청서식(기술거래기관 지정 B).hwp",
                "downloadUrl": "https://example.go.kr/b",
            },
        ],
    )
    assert submission_documents(request.documents[0])[0].form is None


def test_reordered_zip_member_form_keeps_archive_url():
    request = with_document(
        checklist=["기술거래기관 지정신청서"],
        contentText="제출서류: 기술거래기관 지정신청서",
        attachments=[
            {
                "fileName": "제출양식.zip",
                "downloadUrl": "https://example.go.kr/download?id=7&seq=2",
                "extractedText": "[파일: 양식/신청서식(기술거래기관 지정).hwp]\n기관명: ____",
            },
        ],
    )
    body = TemplateReportGenerator().generate(request).summary
    assert "[기술거래기관 지정신청서 (ZIP)](https://example.go.kr/download?id=7&seq=2)" in body


def test_multiline_checklist_in_form_bundle_does_not_prove_included_template():
    request = with_document(
        checklist=["사업계획서"],
        contentText="제출서류: 사업계획서",
        attachments=[
            {
                "fileName": "신청서식.hwpx",
                "downloadUrl": "https://example.go.kr/bundle",
                "extractedText": "제출서류\n사업계획서\n기관명: ____",
            },
        ],
    )
    assert submission_documents(request.documents[0])[0].form is None


def test_spain_confirmation_and_eligibility_forms_resolve_real_zip_members():
    titles = [
        "주관 및 공동연구개발기관 대표의 참여의사 확인서",
        "참여연구자의 개인정보·과세정보 제공·청렴 보안서약서",
        "주관 및 공동연구개발기관의 신청자격 확인서",
    ]
    filenames = [
        titles[0],
        "참여연구자의 개인정보, 과세정보 제공활용 동의 및 청렴 보안서약서",
        "주관 및 공동연구개발기관의 신청자격",
    ]
    req = with_document(
        checklist=titles,
        attachments=[
            {
                "fileName": "붙임1. 제출서류 양식.zip",
                "downloadUrl": "https://example.go.kr/actual-archive",
                "extractedText": "".join(
                    f"[파일: (양식{i}) {name}.hwp]\n기관명:____\n"
                    for i, name in enumerate(filenames)
                ),
            }
        ],
    )
    items = submission_documents(req.documents[0])
    assert all(
        item.form and item.form.url == "https://example.go.kr/actual-archive" for item in items
    )
    assert [item.form.name for item in items] == [
        f"(양식{i}) {name}.hwp" for i, name in enumerate(filenames)
    ]
