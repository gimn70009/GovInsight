import pytest

from app.domains.analysis.proposals.submission_requirements import collect_submission_requirements
from app.domains.analysis.proposals.submission_validation import validate_submission_documents
from app.domains.analysis.schemas.result import PreparationChecklistItem, RequirementSource
from tests.domains.analysis.proposals.test_submission_requirements import FORMS, TABLE, request


def candidate(req, title, quote, *, stage="APPLICATION", name=None):
    return PreparationChecklistItem(
        title=title,
        detail="AI가 원문을 해석하여 추가한 제출 준비 항목입니다.",
        next_action="법무팀에서 반도체 직무 3개를 작성합니다.",
        stage=stage,
        requirement_level="MANDATORY",
        applies_to="참여기업",
        source=RequirementSource(
            origin="ATTACHMENT",
            attachment_name=name or req.attachments[0].file_name,
            section_title="제출 안내",
            excerpt=quote,
        ),
    )


def test_observed_duplicates_and_invented_obligations_are_not_saved_as_required_files():
    evaluation = "인턴십 사전교육 프로그램의 체계성 및 효과성 15"
    eligibility = "참여기업 기업(풀) 구성 시 신청자격 확인(증빙 자료 등) 필수"
    req = request(TABLE + FORMS + "\n" + eligibility)
    req.attachments.append(
        req.attachments[0].model_copy(
            update={
                "attachment_id": 2,
                "file_name": "기본계획.pdf",
                "extracted_text": evaluation,
            }
        )
    )
    original = [row.as_item() for row in collect_submission_requirements(req)]
    original += [
        candidate(
            req,
            "기관소개 자료(기업 프로필)",
            "기관소개 자료 ▸최초 참여 시 또는 홍보 목적 등 필요성이 있을 경우 제출",
        ),
        candidate(
            req,
            "표준 현장실습학기제 협약서(회사 서명·직인 포함)",
            "[별첨 2] 표준 현장실습 학기제 협약서 – 협약 시 제출",
            stage="AGREEMENT",
        ),
        candidate(req, "기업소개서 및 참여기업 연락처 목록", eligibility),
        candidate(req, "직무기술서 및 사전교육 계획서(회사 발행)", evaluation, name="기본계획.pdf"),
        candidate(req, "참여기업 제출용 기본서류(사업자등록증, 기관소개서 등)", eligibility),
        candidate(
            req, "사업계획서 내 인턴십 직무기술서 준비 필요성", evaluation, name="기본계획.pdf"
        ),
    ]
    before = [item.model_dump() for item in original]
    docs, notes = validate_submission_documents(original, req)
    assert len(original) == 13
    assert len(docs) == 7
    assert len(notes) == 4
    assert len([item for item in docs if "기관소개" in item.title]) == 1
    assert len([item for item in docs if "협약서" in item.title]) == 1
    conditional = [item for item in docs if "등록증" in item.title or "기관소개" in item.title]
    assert all(item.requirement_level == "CONDITIONAL" for item in conditional)
    assert all("최초 참여" in item.applies_to for item in conditional)
    assert all("법무팀" not in item.next_action and "3개" not in item.next_action for item in docs)
    assert [item.model_dump() for item in original] == before
    assert validate_submission_documents(docs, req) == (docs, [])


@pytest.mark.parametrize(
    "quote",
    [
        "사전교육 계획서의 체계성과 효과성 15점",
        "기업소개서의 연락처를 정확하게 기재합니다.",
        "참여기업의 신청자격 증빙자료 확인 필수",
        "사업계획서는 제출하지 않습니다.",
        "사업계획서 제출 불필요",
        "사업계획서는 참고용 작성 예시입니다.",
    ],
)
def test_a_real_quote_does_not_by_itself_prove_a_required_file(quote):
    req = request(quote)
    title = (
        "사전교육 계획서"
        if "사전교육" in quote
        else ("기업소개서" if "기업소개서" in quote else "사업계획서")
    )
    docs, notes = validate_submission_documents([candidate(req, title, quote)], req)
    assert docs == []
    assert len(notes) == 1 and notes[0].startswith("제출 여부 확인:")


@pytest.mark.parametrize(
    "text,quote,stage,level",
    [
        (
            "사업계획서를 제출해야 합니다.",
            "사업계획서를 제출해야 합니다.",
            "APPLICATION",
            "MANDATORY",
        ),
        ("신청서류\n사업계획서 1부\n문의처", "사업계획서 1부", "APPLICATION", "MANDATORY"),
        (
            "최초 참여 시 사업계획서를 제출합니다.",
            "최초 참여 시 사업계획서를 제출합니다.",
            "APPLICATION",
            "CONDITIONAL",
        ),
        (
            "협약 시 사업계획서를 제출합니다.",
            "협약 시 사업계획서를 제출합니다.",
            "AGREEMENT",
            "MANDATORY",
        ),
    ],
)
def test_explicit_obligations_survive_without_a_supported_table(text, quote, stage, level):
    req = request(text)
    docs, notes = validate_submission_documents([candidate(req, "사업계획서", quote)], req)
    assert len(docs) == 1 and not notes
    assert docs[0].stage == stage
    assert docs[0].requirement_level == level
    assert "법무팀" not in docs[0].next_action


@pytest.mark.parametrize(
    "text",
    [
        "신청서류\n확인서 1부\n평가 기준\n사업계획서 1부",
        "참고자료\n제출서류\n사업계획서 1부",
        "신청서류\n확인서 1부\n[일부 원문 생략]\n사업계획서 1부",
        "[파일: 신청안내.hwp]\n신청서류\n확인서 1부\n[파일: 다른양식.hwp]\n사업계획서 1부",
    ],
)
def test_submission_heading_does_not_cross_section_or_file_boundaries(text):
    req = request(text, name="제출서류.zip" if "[파일:" in text else "신청안내.hwp")
    item = candidate(
        req, "사업계획서", "사업계획서 1부", name="다른양식.hwp" if "[파일:" in text else None
    )
    docs, notes = validate_submission_documents([item], req)
    assert docs == [] and notes


def test_unmatched_bundle_is_reviewed_without_losing_its_unknown_document():
    quote = "참여기업의 추가 자료를 확인합니다."
    req = request(TABLE + FORMS + quote)
    item = candidate(req, "기본서류(사업자등록증, 기관소개서, 추가 확인서)", quote)
    docs, notes = validate_submission_documents([item], req)
    assert len(docs) == 7
    assert "추가 확인서" in notes[0]
    assert all("기본서류" not in doc.title for doc in docs)


@pytest.mark.parametrize(
    "prefix,suffix",
    [
        ("최초 참여 시 ", ""),
        ("", "\n다만 최초 참여 기업만 제출합니다."),
    ],
)
def test_short_quote_cannot_hide_a_conditional_submission(prefix, suffix):
    quote = "사업계획서를 제출해야 합니다."
    req = request(prefix + quote + suffix)
    docs, notes = validate_submission_documents([candidate(req, "사업계획서", quote)], req)
    assert not notes
    assert docs[0].requirement_level == "CONDITIONAL"
    assert "최초 참여" in docs[0].source.excerpt
    assert "최초 참여" in docs[0].applies_to


@pytest.mark.parametrize("prefix", ["참고 예시\n", "평가 기준\n", "선정 이후 제출 안내\n"])
def test_submission_command_cannot_borrow_a_wrong_scope(prefix):
    quote = "사업계획서를 제출해야 합니다."
    req = request(prefix + quote)
    docs, notes = validate_submission_documents([candidate(req, "사업계획서", quote)], req)
    assert not docs and notes


def test_ambiguous_repeated_short_quote_is_not_promoted_to_mandatory():
    quote = "사업계획서를 제출합니다."
    req = request("최초 참여 시 " + quote + "\n협약 시 " + quote)
    docs, notes = validate_submission_documents([candidate(req, "사업계획서", quote)], req)
    assert not docs and notes


def test_real_job_description_enclosure_remains_in_parent_form():
    text = TABLE + FORMS.replace(
        "붙임 서류\n", "붙임 서류\n0. 표준 현장실습학기제(Co-op) 운영 계획 및 직무기술서\n"
    )
    req = request(text)
    docs, notes = validate_submission_documents([], req)
    assert not notes
    parent = next(item for item in docs if "운영계획서" in item.title)
    assert "사업계획서에 포함" in parent.detail
    assert "운영 계획 및 직무기술서" in parent.detail
    assert "운영 계획 및 직무기술서" in parent.next_action
    assert not any("직무기술서" in item.title for item in docs)


def test_confirmed_period_subject_is_recovered_from_notice_when_model_did_not_supply_it():
    from tests.domains.analysis.proposals.test_submission_period_duplicates import fixture

    req, _ = fixture()
    docs, _ = validate_submission_documents([], req)
    item = next(item for item in docs if item.title == "결산재무제표")
    assert item.applies_to == "참여기업"
    assert "참여기업" in item.source.excerpt
    assert "압축 파일로 제출" in item.detail


def test_new_model_output_with_only_unproven_files_keeps_review_notes_and_no_fake_document():
    import asyncio
    from types import SimpleNamespace
    from unittest.mock import AsyncMock

    from app.domains.analysis.proposals.drafting import LangChainProposalGenerationRunner
    from tests.domains.analysis.proposals.test_drafting import ProposalRunner, document, result

    async def scenario():
        doc = document()
        quote = "사전교육 계획서의 체계성과 효과성 15점"
        doc.content_text = quote
        doc.attachments[0].extracted_text = "산업 AI 공급기업이 신청할 수 있습니다. " + quote
        draft = await ProposalRunner().generate(doc, result())
        draft.preparation.submission_documents = [candidate(doc, "사전교육 계획서", quote)]
        runner = LangChainProposalGenerationRunner.__new__(LangChainProposalGenerationRunner)
        runner._settings = SimpleNamespace(max_text_chars=32000, proposal_timeout_seconds=5)
        runner._draft_model = SimpleNamespace(ainvoke=AsyncMock(return_value=draft))
        generated = await runner.generate(doc, result())
        assert generated.preparation.submission_documents == []
        assert all("제출 여부 확인:" not in item for item in generated.preparation.meeting_agenda)
        assert any(
            "제출 여부 확인: 사전교육 계획서" in s
            for s in generated.preparation.submission_review_notes
        )
        runner._draft_model.ainvoke.assert_awaited_once()

    asyncio.run(scenario())


@pytest.mark.parametrize("years,review", [("’23~’25", False), ("’22~’24", True)])
def test_unverified_year_alias_is_only_covered_by_the_same_confirmed_years(years, review):
    from tests.domains.analysis.proposals.test_submission_period_duplicates import fixture

    req, item = fixture()
    item.title = f"결산재무제표 ({years})"
    item.source = None
    docs, notes = validate_submission_documents([item], req)
    assert len([doc for doc in docs if "재무제표" in doc.title]) == 1
    assert bool(notes) == review


def test_new_model_unverifiable_citation_remains_a_review_note():
    import asyncio
    from types import SimpleNamespace
    from unittest.mock import AsyncMock

    from app.domains.analysis.proposals.drafting import LangChainProposalGenerationRunner
    from tests.domains.analysis.proposals.test_drafting import ProposalRunner, document, result

    async def scenario():
        doc = document()
        draft = await ProposalRunner().generate(doc, result())
        draft.preparation.submission_documents.append(
            candidate(
                doc,
                "개인정보 동의서 및 참여 확약서",
                "원문에 없는 인용문을 만들어서 제출해야 합니다.",
            )
        )
        runner = LangChainProposalGenerationRunner.__new__(LangChainProposalGenerationRunner)
        runner._settings = SimpleNamespace(max_text_chars=32000, proposal_timeout_seconds=5)
        runner._draft_model = SimpleNamespace(ainvoke=AsyncMock(return_value=draft))
        generated = await runner.generate(doc, result())
        assert [item.title for item in generated.preparation.submission_documents] == ["사업계획서"]
        assert any(
            "개인정보 동의서 및 참여 확약서" in s
            for s in generated.preparation.submission_review_notes
        )

    asyncio.run(scenario())


def test_later_unlinked_form_does_not_supply_contents_to_application_form():
    req = request(TABLE + FORMS + "\n표준 현장실습 학기제 협약서\n붙임 서류\n1. 외부 확인서")
    docs, _ = validate_submission_documents([], req)
    assert not any("외부 확인서" in doc.detail for doc in docs)


@pytest.mark.parametrize(
    "title,quote",
    [
        ("최근 3년 결산재무제표", "최근 2년 결산재무제표를 제출해야 합니다."),
        ("신청서(별첨1 양식)", "별첨2 신청서를 제출해야 합니다."),
        ("신청서(대학 제출용)", "참여기업은 신청서를 제출해야 합니다."),
    ],
)
def test_additional_candidate_cannot_invent_period_form_or_submitter(title, quote):
    req = request(quote)
    docs, notes = validate_submission_documents([candidate(req, title, quote)], req)
    assert docs == [] and notes


@pytest.mark.parametrize("change", ["period", "stage", "language", "form"])
def test_distinct_verified_rows_survive_final_validation(change):
    from tests.domains.analysis.proposals.test_submission_period_duplicates import ROW

    one = "신청서류\n서류명 파일형식 비고\n" + ROW + "\n유의사항\n"
    two = one
    if change == "period":
        two = two.replace("3개년", "2개년").replace("23~‘25", "24~‘25")
    elif change == "stage":
        two = two.replace("신청서류", "협약 시 제출서류")
    elif change == "form":
        one = one.replace("결산재무제표\npdf", "결산재무제표(별첨1 양식)\npdf")
        two = two.replace("결산재무제표\npdf", "결산재무제표(별첨2 양식)\npdf")
    else:
        one = one.replace("결산재무제표\npdf", "결산재무제표(국문)\npdf")
        two = two.replace("결산재무제표\npdf", "결산재무제표(영문)\npdf")
    req = request(one + two)
    docs, notes = validate_submission_documents([], req)
    assert len(docs) == 2 and not notes


def test_many_review_notes_stay_separate_without_packing_or_losing_candidates():
    from app.domains.analysis.proposals.submission_review import separate_submission_reviews

    agenda = [f"기존 회의 안건 {i}" for i in range(8)]
    notes = [f"제출 여부 확인: 미확인 서류 {i}" for i in range(27)]
    decisions, reviews = separate_submission_reviews(agenda, notes)
    assert decisions == agenda
    assert reviews == notes


def test_legacy_packed_review_notes_are_unpacked_without_moving_real_decisions():
    from app.domains.analysis.proposals.submission_review import separate_submission_reviews

    notes = [f"제출 여부 확인: 미확인 서류 {i}" for i in range(4)]
    decisions = ["참여 역할을 결정합니다.", "접수 전 제출 여부 확인 담당자를 정합니다."]
    agenda, reviews = separate_submission_reviews(decisions + ["\n".join(notes)], [notes[0]])
    assert agenda == decisions
    assert reviews == notes
    assert separate_submission_reviews(agenda, reviews) == (agenda, reviews)


def test_review_notes_have_a_separate_bounded_api_budget():
    import asyncio

    from pydantic import ValidationError

    from app.domains.analysis.schemas.result import ProposalPreparation
    from tests.domains.analysis.proposals.test_drafting import ProposalRunner, document, result

    draft = asyncio.run(ProposalRunner().generate(document(), result()))
    payload = draft.preparation.model_dump()
    payload["submission_review_notes"] = ["확인할 서류"] * 64
    assert len(ProposalPreparation.model_validate(payload).submission_review_notes) == 64
    for invalid in (["확인할 서류"] * 65, ["가" * 501], [""]):
        payload["submission_review_notes"] = invalid
        with pytest.raises(ValidationError):
            ProposalPreparation.model_validate(payload)
    payload["submission_review_notes"] = []
    for invalid in (["회의 안건"] * 21, ["가" * 501]):
        payload["meeting_agenda"] = invalid
        with pytest.raises(ValidationError):
            ProposalPreparation.model_validate(payload)


def test_model_preparation_checks_do_not_reappear_as_uncertain_submission_obligations():
    import asyncio
    from types import SimpleNamespace
    from unittest.mock import AsyncMock

    from app.domains.analysis.proposals.drafting import (
        LangChainProposalGenerationRunner,
        ProposalPreparationModelOutput,
    )
    from tests.domains.analysis.proposals.test_drafting import ProposalRunner, document, result

    properties = ProposalPreparationModelOutput.model_json_schema()["properties"]
    assert "submissionReviewNotes" not in properties

    async def scenario():
        doc = document()
        draft = await ProposalRunner().generate(doc, result())
        duplicate = "사업계획서 — 담당자와 원본 준비 가능 여부를 확인합니다."
        draft.preparation.submission_review_notes = [duplicate]
        runner = LangChainProposalGenerationRunner.__new__(LangChainProposalGenerationRunner)
        runner._settings = SimpleNamespace(max_text_chars=32000, proposal_timeout_seconds=5)
        runner._draft_model = SimpleNamespace(ainvoke=AsyncMock(return_value=draft))
        generated = await runner.generate(doc, result())
        assert duplicate not in generated.preparation.submission_review_notes
        assert any(
            item.title == "사업계획서" for item in generated.preparation.submission_documents
        )
        runner._draft_model.ainvoke.assert_awaited_once()

    asyncio.run(scenario())
