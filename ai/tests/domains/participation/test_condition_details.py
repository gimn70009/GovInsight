import asyncio

import pytest

from app.domains.participation import focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source

MAIN = "공급망안정품목 또는 전략물자를 생산하는 중소·중견기업이 신청할 수 있습니다."
DEFINITION = "전략물자는 대외무역법에 따라 지정·고시되는 품목입니다."
CONFIRMATION = "공급망안정품목 해당 여부는 확인서를 통해 확인합니다."


def fixture():
    sources = [
        Source(id="a", excerpt=MAIN, group_label="지원대상"),
        Source(id="b", excerpt=DEFINITION + "\n" + CONFIRMATION, group_label="지원대상"),
    ]
    request = RewriteRequest(
        sources=sources,
        criteria=[Criterion(code=code, label=code, category="신청 자격") for code in focused.CODES],
    )
    item = focused.ExtractedCondition(
        source_id="a",
        criterion="SECTOR",
        kind="ELIGIBILITY",
        text=MAIN,
        quote="L1",
        details=[
            focused.ExtractedDetail(source_id="b", text=DEFINITION, quote="L1"),
            focused.ExtractedDetail(source_id="b", text=CONFIRMATION, quote="L2"),
        ],
    )
    return request, item


def test_parent_owns_definitions_and_cross_source_proofs_without_extra_conditions():
    request, item = fixture()
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), request.sources, request
    )
    assert len(result.statements) == 1
    parent = result.statements[0]
    assert parent.criterion == "SECTOR" and parent.text == MAIN
    assert parent.supporting_quotes == [MAIN]
    assert [detail.text for detail in parent.details] == [DEFINITION, CONFIRMATION]
    assert [detail.supporting_quotes for detail in parent.details] == [[DEFINITION], [CONFIRMATION]]
    assert result.evidence_only_ids == []


def test_bad_explanation_is_rejected_without_losing_parent_or_valid_sibling():
    request, item = fixture()
    item.details[1].quote = "L99"
    item.details.append(
        focused.ExtractedDetail(source_id="unselected", text="없는 설명입니다.", quote="L1")
    )
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), request.sources, request
    )
    assert result.statements[0].text == MAIN
    assert [detail.text for detail in result.statements[0].details] == [DEFINITION]
    assert result.evidence_only_ids == ["b"]


def test_independent_requirements_and_exclusions_keep_their_own_rows_in_one_call():
    request, parent = fixture()
    request.sources += [
        Source(id="c", excerpt="업력 7년 이내 기업이 신청할 수 있습니다.", group_label="신청자격"),
        Source(
            id="d", excerpt="국세 체납기업은 지원에서 제외됩니다.", group_label="지원 제외 대상"
        ),
    ]
    items = [
        parent,
        focused.ExtractedCondition(
            source_id="c",
            criterion="BUSINESS_AGE",
            kind="ELIGIBILITY",
            text=request.sources[2].excerpt,
            quote="L1",
            scope="성장형",
        ),
        focused.ExtractedCondition(
            source_id="d",
            criterion="CREDIT",
            kind="EXCLUSION",
            text=request.sources[3].excerpt,
            quote="L1",
        ),
    ]

    class Model:
        calls = 0

        def with_structured_output(self, *args, **kwargs):
            return self

        async def ainvoke(self, *args, **kwargs):
            self.calls += 1
            return focused.Extraction(items=items, unresolved_source_ids=[])

    model = Model()
    result = asyncio.run(focused.extract(request, model))
    assert model.calls == 1
    assert [item.kind for item in result.statements] == ["ELIGIBILITY", "ELIGIBILITY", "EXCLUSION"]
    assert result.statements[1].scope == "성장형"
    assert len(result.statements[0].details) == 2
    assert result.statements[1].details == result.statements[2].details == []


def test_separate_main_and_detail_spans_preserve_long_evidence_within_each_quote_limit():
    request, parent = fixture()
    definition = ("전략물자는 대외무역법에 따라 지정·고시되는 품목입니다. " * 9).strip()
    confirmation = ("공급망안정품목 해당 여부는 관련 확인서로 확인합니다. " * 12).strip()
    excerpt = "\n".join([MAIN, definition, confirmation])
    assert len(excerpt) > 600
    assert max(len(definition), len(confirmation)) <= 600
    request.sources = [Source(id="a", excerpt=excerpt, group_label="지원대상")]
    parent.details = [
        focused.ExtractedDetail(source_id="a", text=DEFINITION, quote="L2"),
        focused.ExtractedDetail(source_id="a", text=CONFIRMATION, quote="L3"),
    ]
    result = focused.project(
        focused.Extraction(items=[parent], unresolved_source_ids=[]), request.sources, request
    )
    assert len(result.statements) == 1
    assert result.statements[0].supporting_quotes == [MAIN]
    assert [d.supporting_quotes for d in result.statements[0].details] == [
        [definition], [confirmation]
    ]
    assert result.evidence_only_ids == []

    # A broad main span is rejected even though the line-reference string itself is short.
    parent.quote = "L1-L3"
    too_broad = focused.project(
        focused.Extraction(items=[parent], unresolved_source_ids=[]), request.sources, request
    )
    assert too_broad.statements == []
    assert too_broad.evidence_only_ids == ["a"]


def test_complete_main_without_extra_details_keeps_evidence_in_one_call():
    main = (
        "협회·단체는 업종별로 대표성을 보유한 비영리법인으로서 "
        "주무부처에 등록된 협회 또는 단체여야 합니다."
    )
    quote = (
        "협회·단체: 업종별로 대표성을 보유한 협회 또는 단체. "
        "비영리법인으로서 주무부처에 등록된 협회 또는 단체에 한함."
    )
    request, _ = fixture()
    request.sources = [Source(id="a", excerpt=quote, group_label="신청자격")]
    item = focused.ExtractedCondition(
        source_id="a", criterion="BUSINESS_FORM", kind="ELIGIBILITY",
        audience="협회·단체", text=main, quote="L1", details=[],
    )

    class Model:
        calls = 0

        def with_structured_output(self, *args, **kwargs):
            return self

        async def ainvoke(self, *args, **kwargs):
            self.calls += 1
            return focused.Extraction(items=[item], unresolved_source_ids=[])

    # The fake response checks integration, not whether a live model follows the prompt.
    model = Model()
    result = asyncio.run(focused.extract(request, model))
    assert model.calls == 1
    assert len(result.statements) == 1
    parent = result.statements[0]
    assert parent.text == main and parent.audience == "협회·단체"
    assert parent.source_ids == ["a"] and parent.supporting_quotes == [quote]
    assert parent.details == []
    assert result.evidence_only_ids == []


@pytest.mark.parametrize(
    ("main", "extra", "criterion"),
    [
        (MAIN, DEFINITION, "SECTOR"),
        (MAIN, CONFIRMATION, "SECTOR"),
        (
            "특허 보유기업이 신청할 수 있으며, 공동특허는 지분 요건을 충족해야 합니다.",
            "공동특허는 신청기업 지분이 50% 이상인 경우에 인정합니다.",
            "IP",
        ),
    ],
    ids=["definition", "proof", "exception-condition"],
)
def test_same_quote_can_support_additional_detail_not_present_in_main(main, extra, criterion):
    request, _ = fixture()
    quote = main + " " + extra
    request.sources = [Source(id="a", excerpt=quote, group_label="신청자격")]
    item = focused.ExtractedCondition(
        source_id="a", criterion=criterion, kind="ELIGIBILITY", text=main, quote="L1",
        details=[focused.ExtractedDetail(source_id="a", text=extra, quote="L1")],
    )
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), request.sources, request
    )
    assert len(result.statements) == 1
    parent = result.statements[0]
    assert parent.text == main and parent.supporting_quotes == [quote]
    assert len(parent.details) == 1
    assert parent.details[0].text == extra
    assert parent.details[0].source_ids == ["a"]
    assert parent.details[0].supporting_quotes == parent.supporting_quotes
    assert result.evidence_only_ids == []
