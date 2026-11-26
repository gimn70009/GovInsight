"""Notice-independent contracts: effects, budgets, aliases and linked exemptions."""

import asyncio
import json

import pytest

from app.domains.participation import applicability, completeness, focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source


def request(*sources):
    return RewriteRequest(
        sources=list(sources),
        criteria=[Criterion(code=code, label=code, category="자격") for code in focused.CODES],
    )


@pytest.mark.parametrize(
    "effect", ["선정평가 대상에서 제외", "심사 대상에서 제외", "지원 제외", "참여 제한"]
)
def test_admission_effects_keep_every_ground_under_administrative_heading(effect):
    grounds = (f"ㅇ 다음의 경우 {effect}될 수 있음\n"
               "- 조사 항목 미작성\n- 기관장 직인 누락\n- 사업 목적 불부합\n")
    source = Source(
        id="n",
        group_label="유의사항",
        excerpt="3\n유의사항\n" + grounds + "4\n신청 방법\nㅇ 홈페이지에서 접수\n",
    )
    selected = focused.select_sources(request(source))
    assert len(selected) == 1
    assert grounds in selected[0].excerpt
    assert "홈페이지" not in selected[0].excerpt


def test_coarse_numbered_document_keeps_qualifications_without_overview_noise():
    source = Source(
        id="n",
        group_label="사업 개요",
        excerpt=(
            "1\n사업 목적\nㅇ 산업 경쟁력 강화\n2\n추진 체계\nㅇ 관리기관 설명\n"
            "3\n지원 대상 (신청자격)\nㅇ 전문성을 보유한 법인 또는 대학\n"
            "ㅇ 단독 또는 컨소시엄으로 신청 가능\n4\n지원 규모\nㅇ 과제별 운영비 지원\n"
        ),
    )
    selected = focused.select_sources(request(source))
    assert "전문성" in selected[0].excerpt and "컨소시엄" in selected[0].excerpt
    assert "경쟁력 강화" not in selected[0].excerpt
    assert "과제별 운영비" not in selected[0].excerpt


@pytest.mark.parametrize("name", ["지역혁신법", "산업협력촉진법", "기술개발지원법"])
@pytest.mark.parametrize("suffix", ["시행령", "시행규칙"])
def test_unique_parent_law_allows_shorthand_but_never_a_different_article(name, suffix):
    original = f"「{name}」 제12조 및 「{name}{suffix}」 제8조에 따른 제한"
    assert completeness.laws_preserved(
        original, f"{name} 제12조 및 {suffix} 제8조에 따른 제한입니다."
    )
    assert not completeness.laws_preserved(
        original, f"{name} 제12조 및 {suffix} 제9조에 따른 제한입니다."
    )
    assert not completeness.laws_preserved(
        original, f"다른법 제12조 및 {suffix} 제8조에 따른 제한입니다."
    )


def test_ambiguous_law_parent_never_resolves_a_bare_decree():
    original = "「혁신법」 제3조 및 「진흥법 시행령」 제8조"
    assert not completeness.laws_preserved(original, "혁신법 제3조 및 시행령 제8조에 따릅니다.")
    assert not completeness.laws_preserved(
        "「진흥법」 제3조 및 「진흥법 시행령」 제8조",
        "다른진흥법 제3조 및 시행령 제8조에 따릅니다.",
    )
    assert completeness.laws_preserved(
        "「진흥법」 제3조 및 「진흥법 시행령」 제8조",
        "「진흥법」 제3조 및 「같은 법 시행령」 제8조에 따릅니다.",
    )


def test_shortened_law_in_a_complete_exclusion_is_visible_after_projection():
    quote = ("「혁신법」 제12조 및 「혁신법시행령」 제8조에 따라 "
             "접수일 현재 참여제한 중인 자는 신청 불가")
    source = Source(id="n", group_label="신청자격", excerpt=quote)
    item = focused.ExtractedCondition(
        source_id="n",
        criterion="BUSINESS_FORM",
        kind="EXCLUSION",
        quote="L1",
        text="혁신법 제12조 및 시행령 제8조에 따라 접수일 현재 참여제한 중인 자는 신청 불가합니다.",
    )
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), [source], request(source)
    )
    assert not result.statements[0].review_required
    assert "REQUIRED_CONDITION_INCOMPLETE" not in result.diagnostics


def linked_sources():
    return (
        Source(
            id="form",
            group_label="[공고 대상] 지역 연구기관 모집\n[자격 확인서] 수행 제한",
            excerpt=("연구책임자의 동시수행 과제 수는 최대 4개인가?\n"
                     "신청과제 참여율은 15% 이상인가?"),
        ),
        Source(
            id="exception",
            group_label="[공고 대상] 지역 연구기관 모집\n[확인서 적용 예외] 수행 제한",
            excerpt="본 사업 주관기관 연구책임자는 동시수행 과제 수 산정에서 제외합니다.",
        ),
    )


def test_checklist_cannot_override_a_linked_exemption_even_when_its_quote_is_exact():
    form, exception = linked_sources()
    text = "연구책임자의 동시수행 과제 수는 최대 4개입니다."
    item = focused.ExtractedCondition(
        source_id="form", criterion="BUSINESS_FORM", kind="EXCLUSION", quote="L1", text=text
    )
    req = request(form, exception)
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), req.sources, req
    )
    assert result.statements[0].review_required
    assert result.diagnostics["NOTICE_EXEMPTION_UNRESOLVED"] == 1
    context = json.loads(focused.model_messages(req.sources)[1].content)["sources"][0]["context"]
    assert context["exceptions"] == ["exception"]


def test_other_notice_and_different_constraint_do_not_borrow_an_exemption():
    form, exception = linked_sources()
    assert not applicability.conflict(form, "참여율은 15% 이상이어야 합니다.", [], [], [exception])
    other = exception.model_copy(
        update={"group_label": "[공고 대상] 제조기업 모집\n[확인서 적용 예외] 수행 제한"}
    )
    assert not applicability.conflict(form, "동시수행 과제 수는 최대 4개입니다.", [], [], [other])
    # The same recruitment and dimension must both be cited and visible in the main sentence.
    assert not applicability.conflict(
        form,
        "주관기관 연구책임자는 동시수행 과제 수 산정에서 제외됩니다.",
        [],
        [exception.excerpt],
        [exception],
    )
    assert applicability.conflict(
        form,
        "동시수행 과제 수는 최대 4개입니다.",
        [exception.excerpt],
        [exception.excerpt],
        [exception],
    )


def test_reference_manual_noise_does_not_displace_an_explicit_qualification():
    noise = Source(
        id="manual",
        group_label="[참고 규정] 사업비 사용기준.pdf\n집행 절차",
        excerpt="대학 및 연구기관은 사업비를 정산해야 합니다.",
    )
    genuine = Source(
        id="qual",
        group_label="[참고 규정] 운영관리지침.pdf\n신청자격",
        excerpt="신청자격\n비영리기관만 신청할 수 있습니다.",
    )
    assert [s.id for s in focused.select_sources(request(noise, genuine))] == ["qual"]


def test_exemption_neither_crosses_explicit_roles_nor_accepts_reversed_meaning():
    form, exception = linked_sources()
    assert not applicability.conflict(
        form, "공동기관의 동시수행 과제 수는 최대 4개입니다.", [], [], [exception]
    )
    assert applicability.conflict(
        form, "주관기관 연구책임자는 동시수행 과제 수 산정에서 제외되지 않습니다.",
        [], [exception.excerpt], [exception]
    )
    assert applicability.criterion(
        "BUSINESS_FORM", "운영인력(매니저)은 주관기관 직원으로 구성할 수 있습니다."
    ) == "EXPERIENCE"
    assert applicability.criterion(
        "BUSINESS_FORM", "전문인력 양성사업의 신청 주체는 비영리기관입니다."
    ) == "BUSINESS_FORM"


def test_large_attachment_collection_reaches_one_bounded_model_call():
    req = request(
        *(
            Source(
                id=f"s{i}",
                group_label="신청자격",
                excerpt=f"유형 {i}의 신청기업은 중소기업이어야 합니다.",
            )
            for i in range(800)
        )
    )

    class Model:
        calls = 0

        def with_structured_output(self, *args, **kwargs):
            return self

        async def ainvoke(self, messages, **kwargs):
            self.calls += 1
            payload = json.loads(messages[1].content)
            assert 0 < len(payload["sources"]) <= focused.MAX_SOURCES
            assert (
                focused.call_budget().reserve(messages, focused.Extraction)
                <= focused.MAX_INPUT_BYTES
            )
            return focused.Extraction(items=[], unresolved_source_ids=[])

    model = Model()
    result = asyncio.run(focused.extract(req, model))
    assert model.calls == 1
    assert result.diagnostics["INPUT_SOURCE_UNSELECTED"] > 0


def test_program_roles_do_not_replace_named_tracks_or_applicant_labels():
    from app.domains.participation import context_labels

    assert context_labels.program_scope("협의체", "협의체는 신청 가능합니다.") is None
    assert context_labels.program_scope("주관지역 / 연계지역", "지역별 회원 요건") is None
    assert context_labels.audience_label("협의체", "협의체는 신청 가능합니다.") == "협의체"
    assert context_labels.program_scope("협의체", "사업명\t지원대상\n협의체\t기관") == "협의체"
    assert context_labels.program_scope("협의체 지원사업", "공고") == "협의체 지원사업"
    unrelated = "사업명\t지원대상\n협의체\t기관\n[/표]\n협의체는 7명을 구성해야 함"
    assert context_labels.program_scope("협의체", unrelated, "협의체는 7명을 구성해야 함") is None


def test_notice_title_is_source_local_context_not_a_generated_program_label():
    sources = [Source(id="a", group_label="[공고 제목] 지역 협력 지원사업 공고\n신청자격",
                      excerpt="대학이 신청할 수 있습니다."),
               Source(id="b", group_label="[공고 제목] 기술 이전 지원사업 공고\n신청자격",
                      excerpt="법인이 신청할 수 있습니다.")]
    payload = json.loads(focused.model_messages(sources)[1].content)
    assert payload["notice_titles"] == ["지역 협력 지원사업 공고", "기술 이전 지원사업 공고"]
    assert [s["context"]["notice"] for s in payload["sources"]] == [0, 1]


def test_contribution_is_finance_but_sales_comparison_stays_sales():
    assert (
        applicability.criterion("REVENUE", "자기부담금은 사업비의 25% 이상이어야 합니다.")
        == "CREDIT"
    )
    assert (
        applicability.criterion("REVENUE", "매출 대비 자부담 비율을 충족해야 합니다.") == "REVENUE"
    )
