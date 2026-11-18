import asyncio

import pytest

from app.domains.participation import focused
from app.domains.participation.presentation import complete_sentence
from app.domains.participation.schemas import RewriteResponse
from tests.domains.participation.test_condition_details import DEFINITION, MAIN, fixture


@pytest.mark.parametrize("text", [
    "연구기관에는 국공립·민간 연구기관이 포함됩니다.",
    "법 제2조에 따른 기관이며, 제3조 요건도 충족해야 합니다.",
    "지분은 50.5% 이상이어야 합니다. 다만 공동소유는 제외합니다.",
    "기관별 기준을 따릅니다.",
    "등록 특허를 보유해야 합니다. 다만 출원 중인 특허는 인정하지 않습니다.",
])
def test_complete_sentences_preserve_law_numbers_alternatives_and_exceptions(text):
    assert complete_sentence(text)


@pytest.mark.parametrize("text", [
    "협·단체, 연구기관 등 단독 또는 컨소시엄",
    "업력 7년 이내 기업.",
    "기관등록 등\n※ 기관대표자 정보 사전 조치",
    "증빙 제출 필요. 미제출 기관은 제외됩니다.",
    "① 기관등록이 필요합니다.",
    "기관은 신청 가능합니다. - 다만 등록이 필요합니다.",
])
def test_raw_fragments_are_not_repaired_with_automatic_endings(text):
    assert not complete_sentence(text)


def test_failed_detail_stays_attached_to_evidence_and_valid_sibling_in_one_call():
    request, item = fixture()
    raw = "해당 품목 확인서 제출 필요"
    request.sources[1].excerpt += "\n" + raw
    item.details[1].text, item.details[1].quote = raw, "L3"

    class Model:
        calls = 0

        def with_structured_output(self, *args, **kwargs):
            return self

        async def ainvoke(self, *args, **kwargs):
            self.calls += 1
            return focused.Extraction(items=[item], unresolved_source_ids=[])

    model = Model()
    result = asyncio.run(focused.extract(request, model))
    restored = RewriteResponse.model_validate_json(result.model_dump_json())
    parent = restored.statements[0]
    assert model.calls == 1
    assert parent.text == MAIN and not parent.review_required
    assert parent.details[0].text == DEFINITION and not parent.details[0].review_required
    assert parent.details[1].text == raw and parent.details[1].review_required
    assert parent.details[1].supporting_quotes == [raw]


def test_invalid_main_is_marked_instead_of_discarding_its_conditions():
    request, item = fixture()
    item.text = "공급망안정품목 생산 중소기업"
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), request.sources, request
    )
    assert result.statements[0].review_required
    assert result.statements[0].supporting_quotes == [MAIN]
    assert len(result.statements[0].details) == 2
