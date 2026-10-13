"""Regression for source glyphs and financial notes leaking into participation rows."""

from dataclasses import replace

import pytest

from app.domains.report.brief import build_context, fallback_brief, validate_brief
from app.domains.report.presentation import submission_display_lines
from app.domains.report.template import TemplateReportGenerator
from tests.domains.report.test_report_brief import output, sourced
from tests.domains.report.test_report_template import with_document

ROLE = "국내 컨소시엄 내 영리기업 참여 필수"
BUDGET_NOTE = (
    "지원예산 및 지원기간은 명시된 조건 이내에서 지원하되, "
    "평가 결과에 따라 변동될 수 있음"
)


@pytest.mark.parametrize("separator", ["\n* ", " * ", "\n※", "\n단, "])
def test_financial_note_stays_with_amount_without_polluting_participation(separator):
    cap = "과제당 연 5억원 이내 (최대 3년)"
    content = cap + "\n" + ROLE + separator + BUDGET_NOTE
    request = with_document(title="국제공동 기술개발사업 공고", contentText=content)
    document = request.documents[0]
    result = output(participation_roles=[sourced(ROLE, quote=ROLE)])
    brief = validate_brief(result, build_context(document, 16000), document)
    assert brief.opportunity.participation_role == ROLE
    if "*" in separator or "※" in separator:
        assert "평가 결과" in brief.opportunity.amount
        assert "변동될 수 있습니다" in brief.opportunity.amount
    body = TemplateReportGenerator().generate(
        request, briefs={document.version_id: brief},
    ).summary
    assert f"• 참여 역할: {ROLE}\n" in body
    assert document.content_text == content


@pytest.mark.parametrize("note", [
    "단, 일본 영리기업의 참여가 필수이며 대학 단독 신청은 불가함",
    "참여제한 기관은 제외함",
    "프로그램 가이드라인 https://example.org/rules 준수 필수 (미준수 시 지원 제외됨)",
    "지원기간 동안 국내 영리기업이 컨소시엄에 참여해야 함",
    "지원예산은 중소기업에 한정되므로 대기업은 참여할 수 없음",
])
def test_participation_restrictions_survive_cleaning_even_if_quote_omits_note(note):
    request = with_document(
        title="국제공동 기술개발사업 공고", contentText=ROLE + "\n* " + note,
    )
    document = request.documents[0]
    brief = validate_brief(
        output(participation_roles=[sourced(ROLE)]),
        build_context(document, 16000), document,
    )
    assert brief.opportunity.participation_role == ROLE + "\n" + note
    assert submission_display_lines(brief.opportunity.participation_role, "참여 역할") == [
        ROLE, note,
    ]


@pytest.mark.parametrize("marker", ["▸ ", "▸", "▷", "▶", "□", "※ ", "* ", "◦", "ㅇ ", "o "])
def test_contact_outline_markers_are_removed_from_final_report_with_or_without_space(marker):
    contact = "IRIS 문의처: 시스템지원센터 02-0000-0000, help+iris@example.org"
    request = with_document(title="시스템 점검 안내", contentText="시스템 점검으로 문의 안내")
    document = request.documents[0]
    brief = fallback_brief(document, "")
    brief = replace(brief, facts=replace(brief.facts, contact=marker + contact))
    body = TemplateReportGenerator().generate(
        request, briefs={document.version_id: brief},
    ).summary
    assert "• 문의 담당: " + contact in body
    assert marker + contact not in body


def test_inline_bullets_split_contacts_but_keep_phone_email_url_characters():
    first = "IRIS 시스템: 02-0000-0000, iris+help@example.org"
    second = "사업단: 042-000-0000, https://example.org/help/a*b?q=R%26D&step=1"
    assert submission_display_lines("▸" + first + " ▷" + second, "문의 담당") == [first, second]


def test_participation_checkbox_selection_is_not_treated_as_outline_decoration():
    source = "주관기관: □ 중소기업 ■ 대학 □ 출연(연) ※ 단, 국내 기업 참여 필수"
    assert submission_display_lines(source, "참여 역할") == [
        "주관기관: 대학. 단, 국내 기업 참여 필수",
    ]
