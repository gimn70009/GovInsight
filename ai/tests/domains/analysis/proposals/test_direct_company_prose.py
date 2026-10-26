import pytest

from app.domains.analysis.proposals.guidance import (
    ProposalGuidanceError,
    guidance_issues,
    strip_section_preamble,
    verify_proposal_guidance,
)
from app.domains.analysis.proposals.writer import normalize_draft_body

INTRO = (
    "본 항목에서는 당사의 첨단산업 인재양성 사업 수행 현황과 "
    "현장실습·기업 인턴십 연계 운영 역량을 기술합니다."
)
BODY = "당사는 제조 현장의 데이터를 통합합니다.\n\n당사는 운영 방법을 제안하겠습니다."


def test_screenshot_intro_is_removed_and_company_paragraphs_are_preserved():
    assert normalize_draft_body(INTRO + " " + BODY) == BODY
    assert guidance_issues(INTRO)[0]["rule"] == "section_preamble"


@pytest.mark.parametrize("intro", [
    "이 절에서는 당사의 사업 추진 방법을 설명합니다.",
    "해당 작성란에는 당사의 수행 역량을 제시하겠습니다.",
])
def test_variants_are_removed_only_when_company_prose_follows(intro):
    assert strip_section_preamble(intro + " " + BODY) == BODY
    with pytest.raises(ProposalGuidanceError):
        verify_proposal_guidance(intro)


@pytest.mark.parametrize("body", [
    "본 사업에서는 실무 교육을 제공합니다. 당사는 운영을 지원합니다.",
    "당사는 매월 점검 보고서를 작성합니다.",
    "본 항목에서는 추진 방법을 설명합니다.",
    "본 항목에서는 절차를 설명합니다. 기관은 사업을 주관합니다.",
])
def test_substantive_content_or_only_sentence_is_not_deleted(body):
    assert strip_section_preamble(body) == body


def test_english_template_does_not_receive_korean_opening_instruction():
    from app.domains.analysis.proposals.writer import writing_instructions

    instructions = writing_instructions("en")
    assert "'당사는 …'" not in instructions
    assert "We integrate manufacturing data." in instructions
