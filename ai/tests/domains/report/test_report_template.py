from app.domains.report.schemas.request import ReportDocumentRequest, ReportJobRequest
from app.domains.report.template import TemplateReportGenerator, display_units


def report_request() -> ReportJobRequest:
    return ReportJobRequest.model_validate(
        {
            "runId": 10,
            "requestedAt": "2026-09-02T08:30:00",
            "totalSourceCount": 2,
            "detectedDocumentCount": 3,
            "warningCount": 1,
            "documents": [
                {
                    "detectionId": 1,
                    "documentId": 1,
                    "versionId": 1,
                    "organizationName": "국토교통부",
                    "boardName": "공지사항",
                    "changeType": "UNCHANGED_DOCUMENT",
                    "title": "변경 없는 안내",
                    "originalUrl": "https://example.go.kr/1",
                    "summary": "기존 안내와 같은 내용입니다.",
                    "keyPoints": ["기존 조건이 유지됩니다."],
                    "importance": "LOW",
                    "reason": "별도 대응이 필요하지 않습니다.",
                },
                {
                    "detectionId": 2,
                    "documentId": 2,
                    "versionId": 2,
                    "organizationName": "산업통상부",
                    "boardName": "사업공고",
                    "changeType": "NEW_DOCUMENT",
                    "title": "중요 신규 지원사업",
                    "originalUrl": "https://example.go.kr/2",
                    "summary": "신청 기한이 있는 지원사업입니다.",
                    "keyPoints": ["9월 30일까지 신청합니다.", "지원 자격 검토가 필요합니다."],
                    "importance": "HIGH",
                    "reason": "회사 관련성과 대응 기한이 확인됩니다.",
                },
                {
                    "detectionId": 3,
                    "documentId": 3,
                    "versionId": 3,
                    "organizationName": "산업통상부",
                    "boardName": "사업공고",
                    "changeType": "UPDATED_DOCUMENT",
                    "title": "수정된 지원사업",
                    "originalUrl": "https://example.go.kr/3",
                    "summary": "접수 기간이 변경되었습니다.",
                    "keyPoints": [],
                    "importance": "NORMAL",
                    "reason": None,
                },
            ],
        }
    )


def test_generate_report_without_model_call() -> None:
    request = report_request()
    first = request.documents[0].model_copy(update={"opportunity_score": 82})
    request = request.model_copy(update={"documents": [first, *request.documents[1:]]})
    draft = TemplateReportGenerator().generate(request)
    assert draft.title == "[공공기관 모니터링] 9월 2일 보고서"
    assert "신규 1건 │ 수정 1건 │ 변경 없음 1건" in draft.summary
    assert "▸ 변경 없는 안내" in draft.summary
    assert "변경 없음 │ 기회점수: 82점" in draft.summary
    assert "• 제출·의견 기한: 원문 확인 필요" in draft.summary
    assert "• 제출처·방법: 원문 확인 필요" in draft.summary
    assert "원문: [게시글 보기](https://example.go.kr/1)" in draft.summary
    assert "참여를 권장" not in draft.summary


def with_document(**updates):
    request = report_request()
    data = request.documents[1].model_dump(by_alias=True)
    data.update(updates)
    return request.model_copy(update={"documents": [ReportDocumentRequest.model_validate(data)]})


def test_action_fields_keep_source_subject_deadline_time_and_real_downloads():
    request = with_document(
        contentText="신청대상: 비수도권 지방자치단체\n"
        "접수기간: 2026-11-02 09:00 ~ 2026-11-06 16:00 (한국시간)\n"
        "제출처: 사업 담당 부서 / submit@example.go.kr\n제출서류: 신청 공문, 신청서, 계획서\n"
        "문의처: 사업지원팀 (02-0000-0000)",
        attachments=[
            {
                "fileName": "신청서 [서식].hwp",
                "downloadUrl": "https://example.go.kr/file?id=1&name=form(1).hwp",
            }
        ],
        comparisonSummary={
            "purpose": "지역 실증사업 후보과제를 모집합니다.",
            "applicationDeadline": "2026-11-06",
            "eligibility": "지자체",
        },
    )
    body = TemplateReportGenerator().generate(request).summary
    assert "비수도권 지방자치단체" in body
    assert "2026-11-06 16:00 (한국시간)" in body
    assert "submit@example.go.kr" in body
    assert all(title in body for title in ["신청 공문", "신청서", "계획서"])
    assert "사업지원팀 (02-0000-0000)" in body
    assert "[신청서](https://example.go.kr/file?id=1&name=form%281%29.hwp)" in body
    assert "요약: 지역 실증사업 후보과제를 모집합니다." in body


def test_inquiry_address_is_not_submission_address_and_filenames_are_not_requirements():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                contentText="문의처: 상담팀 help@example.go.kr\n"
                "등록일: 2026-09-22\n공개기간: 2026-09-22 ~ 2026-10-06",
                attachments=[
                    {"fileName": "참고용 신청서.hwp", "downloadUrl": "https://example.go.kr/a.hwp"}
                ],
            )
        )
        .summary
    )
    assert "• 제출처·방법: 원문 확인 필요" in body
    assert "• 제출·의견 기한: 원문 확인 필요" in body
    assert "확인된 제출 서류가 없습니다" in body
    assert "• 문의 담당: 상담팀 help@example.go.kr" in body


def test_attachment_text_is_used_for_submission_guidance():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                attachments=[
                    {
                        "fileName": "공고문.pdf",
                        "downloadUrl": "https://example.go.kr/a.pdf",
                        "extractedText": "의견 제출 기한: 2026-10-06 18:00\n"
                        "제출방법: 주민의견서를 온라인으로 제출\n제출서류: 주민의견서",
                    }
                ]
            )
        )
        .summary
    )
    assert "2026-10-06 18:00" in body
    assert "주민의견서를 온라인으로 제출" in body


def test_existing_comparison_deadline_used_without_proposal_preparation():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                comparisonSummary={
                    "applicationDeadline": "한국 측 2027-01-28 16:00, 스페인 측 별도 확인",
                    "eligibility": "한·스페인 공동기관",
                }
            )
        )
        .summary
    )
    assert "한국 측 2027-01-28 16:00, 스페인 측 별도 확인" in body
    assert "한·스페인 공동기관" in body


def test_unsafe_and_duplicate_urls_are_not_rendered_as_downloads():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                contentText="제출서류: 신청서",
                attachments=[
                    {"fileName": "악성 파일", "downloadUrl": "javascript:alert(1)"},
                    {"fileName": "정보 노출", "downloadUrl": "https://user:secret@example.go.kr/f"},
                    {"fileName": "신청서.hwp", "downloadUrl": "https://example.go.kr/form.hwp"},
                    {"fileName": "중복", "downloadUrl": "https://example.go.kr/form.hwp"},
                ],
            )
        )
        .summary
    )
    assert "javascript:" not in body and "secret" not in body
    assert body.count("https://example.go.kr/form.hwp") == 1


def test_long_links_survive_and_display_budget_uses_utf16():
    request = with_document(
        contentText="제출서류: 신청서",
        attachments=[
            {"fileName": "신청서.hwp", "downloadUrl": "https://example.go.kr/f?token=" + "a" * 2500}
        ],
    )
    body = TemplateReportGenerator().generate(request).summary
    assert "a" * 2500 in body
    assert display_units(body) < 3900
    assert display_units("😀") == 2


def test_many_documents_report_omissions_without_broken_links():
    request = with_document(
        contentText="제출서류: 신청서",
        attachments=[
            {"fileName": "😀신청서.hwp", "downloadUrl": "https://example.go.kr/f?q=" + "b" * 700}
        ],
    )
    request = request.model_copy(update={"documents": request.documents * 50})
    body = TemplateReportGenerator().generate(request).summary
    assert display_units(body) <= 3900
    assert len(body.encode("utf-16-le")) // 2 <= 20000
    assert "그 외" in body
    assert "원문: [게시글 보기](https://example.go.kr/2)" in body
    assert body.count("](https://") == len(
        __import__("re").findall(r"\]\(https://[^\s()]+\)", body)
    )


def test_source_markdown_is_not_interpreted_as_generated_link():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(summary="외부 안내 [가짜](https://wrong.example) 문장을 확인합니다.")
        )
        .summary
    )
    assert "[가짜](" not in body


def test_summary_is_one_or_two_sentences_not_full_reason():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                summary="첫 번째 사업 요약입니다. 두 번째 핵심 설명입니다. 세 번째 긴 배경입니다.",
                reason="반복된 긴 참여 의견입니다.",
            )
        )
        .summary
    )
    assert "요약: 첫 번째 사업 요약입니다. 두 번째 핵심 설명입니다." in body
    assert "세 번째" not in body and "반복된 긴" not in body


def test_separate_country_deadlines_and_submission_channels_are_preserved():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                contentText=(
                    "한국 신청기한: 2027-01-28 16:00 (한국시간)\n"
                    "스페인 신청기한: 2027-01-29 14:00 (현지시간)\n"
                    "한국 제출처: 국내 사업관리 시스템\n"
                    "스페인 제출처: 해외 사업관리 시스템"
                )
            )
        )
        .summary
    )
    assert "한국 신청기한: 2027-01-28 16:00 (한국시간)" in body
    assert "스페인 신청기한: 2027-01-29 14:00 (현지시간)" in body
    assert "한국 제출처: 국내 사업관리 시스템" in body
    assert "스페인 제출처: 해외 사업관리 시스템" in body


def test_bullet_labels_are_removed_and_split_table_values_are_found():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                contentText=(
                    "▷ 제출서류 : 신청 공문, 신청서\n"
                    "▶ 제출서류: 신청 공문, 신청서\n"
                    "제출처\nsubmit@example.go.kr\n"
                    "문의처:\n사업지원팀 (02-0000-0000)"
                )
            )
        )
        .summary
    )
    assert "• 신청 공문" in body and "• 신청서" in body
    assert "▷ 제출서류" not in body
    assert body.count("• 신청 공문") == 1
    assert "• 제출처·방법: submit@example.go.kr" in body
    assert "• 문의 담당: 사업지원팀 (02-0000-0000)" in body


def test_empty_submission_heading_does_not_absorb_next_inquiry_heading():
    body = (
        TemplateReportGenerator()
        .generate(with_document(contentText="제출처:\n문의처: help@example.go.kr"))
        .summary
    )
    assert "• 제출처·방법: 원문 확인 필요" in body
    assert "• 문의 담당: help@example.go.kr" in body


def test_table_role_header_is_not_reported_as_applicant():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                contentText="신청대상\n역 할\n구분\n수행기관\n문의처: 사업지원팀 02-0000-0000",
                comparisonSummary={"eligibility": "역 할"},
            )
        )
        .summary
    )
    assert "• 제출 주체·대상: 원문 확인 필요" in body
    assert "• 문의 담당: 사업지원팀 02-0000-0000" in body


def test_numbered_documents_and_applicant_exception_are_kept_together():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                contentText=(
                    "신청대상: 중소기업\n단, 휴업·폐업 기업은 제외합니다.\n"
                    "제출서류\n1. 지정신청서 및 정관 사본\n2. 사업계획서\n3. 납세증명서\n"
                    "문의처: 사업지원팀 02-0000-0000"
                )
            )
        )
        .summary
    )
    assert "단, 휴업·폐업 기업은 제외합니다." in body
    titles = [line for line in body.splitlines() if line.startswith("• ")]
    assert all(
        any(value in title for title in titles)
        for value in ["지정신청서", "사업계획서", "납세증명서"]
    )
    assert all("문의처" not in title for title in titles)


def test_empty_form_cells_are_not_actual_submission_values():
    body = (
        TemplateReportGenerator()
        .generate(with_document(contentText="신청기관: ○○○\n제출처: __________\n담당자: 성 명"))
        .summary
    )
    assert "• 제출 주체·대상: 원문 확인 필요" in body
    assert "• 제출처·방법: 원문 확인 필요" in body


def test_long_filename_uses_short_document_name_without_changing_download_url():
    name = "중소벤처기업부_" + "아주긴사업명_" * 30 + "규제특례_신청서_최종.hwpx"
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                contentText="제출서류: 신청서",
                attachments=[
                    {"fileName": name, "downloadUrl": "https://example.go.kr/file?id=ABC&seq=2"}
                ],
            )
        )
        .summary
    )
    assert "[신청서](https://example.go.kr/file?id=ABC&seq=2)" in body
    assert name not in body


def test_applicant_example_does_not_become_eligibility():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                contentText="신청기관: 예시: 중소기업",
                comparisonSummary={"eligibility": "작성예시: 중소기업"},
            )
        )
        .summary
    )
    assert "• 제출 주체·대상: 원문 확인 필요" in body


def test_windows_line_endings_preserve_numbered_document_list():
    body = (
        TemplateReportGenerator()
        .generate(
            with_document(
                contentText="제출서류\r\n1. 신청서\r\n2. 납세증명서\r\n"
                "문의처: 사업지원팀 02-0000-0000"
            )
        )
        .summary
    )
    assert "• 신청서" in body and "• 납세증명서" in body


def test_damaged_deadline_is_unknown_instead_of_any_line_containing_digits():
    from app.domains.report.facts import submission_facts

    for value in [
        "월 수 시까지 / 수요조사서 접수는 접수마감일 시 이전까지 제출 완료되어야 함 18※ / "
        "수요조사서 작성 제출시 유의사항 / .",
        "2026년 2월 30일",
        "2026-13-12",
        "2026-09-09 ~ 월 일",
    ]:
        document = with_document(
            contentText="접수기간: " + value, comparisonSummary={"applicationDeadline": value}
        ).documents[0]
        assert submission_facts(document).deadline == "원문 확인 필요", value


def test_deadline_stops_before_next_section_and_preserves_both_endpoints():
    from app.domains.report.facts import submission_facts

    value = "2026년 9월 9일(수) ~ 2026년 10월 12일(월)"
    for separator in ["\n", " / "]:
        document = with_document(
            contentText="접수기간: " + value + separator + "접수 및 문의처"
        ).documents[0]
        assert submission_facts(document).deadline == value


def test_invalid_deadline_does_not_hide_later_valid_source():
    from app.domains.report.facts import submission_facts

    document = with_document(
        contentText="접수기간: 월 수 시까지 18※\n제출기한: 2026-10-12 16:00"
    ).documents[0]
    assert submission_facts(document).deadline == "2026-10-12 16:00"
