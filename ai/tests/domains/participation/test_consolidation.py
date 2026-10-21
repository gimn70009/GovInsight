import asyncio
import json

from app.domains.participation.consolidation import Choice, Consolidation, consolidate
from app.domains.participation.schemas import Statement


def statements():
    return [
        Statement(
            source_ids=["a"],
            criterion="BUSINESS_ITEM",
            text=(
                "모든 유형 공통으로 본 사업과 무관한 단순 장비 교체·구매 "
                "또는 자산성 품목 구입은 신청할 수 없습니다."
            ),
            group_label="",
            supporting_quotes=["단순 장비 교체·구매 또는 자산성 품목 구입 불가"],
            kind="EXCLUSION",
        ),
        Statement(
            source_ids=["b"],
            criterion="PRODUCT",
            text=(
                "모든 유형 공통으로 신청 내용은 본 사업과 무관한 단순 장비 교체·구매 "
                "또는 자산성 품목 구입일 수 없습니다."
            ),
            group_label="",
            supporting_quotes=["단순 장비 교체·구매 또는 자산성 품목 구입 불가"],
            kind="EXCLUSION",
        ),
    ]


class Model:
    def __init__(self, keep):
        self.keep = keep
        self.calls = 0

    def with_structured_output(self, *args, **kwargs):
        owner = self

        class Run:
            async def ainvoke(self, messages):
                owner.calls += 1
                return Consolidation(
                    choices=[Choice(pair=0, keep=owner.keep)]
                    if json.loads(messages[-1].content)["pairs"]
                    else []
                )

        return Run()


def test_semantic_duplicates_across_criteria_keep_one_and_all_sources():
    model = Model([1])
    result, unresolved = asyncio.run(consolidate(statements(), [], model))
    assert len(result) == 1 and result[0].criterion == "PRODUCT"
    assert set(result[0].source_ids) == {"a", "b"}
    assert not unresolved and model.calls == 1


def test_different_conditions_and_numeric_tracks_are_not_automatically_merged():
    model = Model([0, 1])
    result, unresolved = asyncio.run(consolidate(statements(), [], model))
    assert len(result) == 2 and not unresolved
    items = statements()
    items[0].text = "100억 트랙은 매출액 100억 원을 달성해야 합니다."
    items[1].text = "500억 트랙은 매출액 500억 원을 달성해야 합니다."
    model = Model([0])
    assert len(asyncio.run(consolidate(items, [], model))[0]) == 2
    assert model.calls == 1


def test_invalid_deduplication_retries_twice_then_preserves_evidence():
    model = Model([99])
    result, unresolved = asyncio.run(consolidate(statements(), [], model))
    assert not result and unresolved == {"a", "b"}
    assert model.calls == 2


def test_scope_review_excludes_post_award_financial_procedures_without_rewriting():
    items = statements()
    items[1] = items[1].model_copy(
        update={
            "criterion": "PLAN",
            "text": (
                "선정된 기업은 기술료 납부고지서를 받은 날부터 "
                "90일 이내에 기술료를 납부해야 합니다."
            ),
        }
    )

    class ScopeModel:
        def with_structured_output(self, *args, **kwargs):
            class Run:
                async def ainvoke(self, messages):
                    return Consolidation(choices=[], out_of_scope=[1])

            return Run()

    kept, unresolved = asyncio.run(consolidate(items, [], ScopeModel()))
    assert kept == [items[0]] and not unresolved


def test_checked_scope_without_similar_pairs_needs_no_extra_model_call():
    items = statements()
    items[0].text = "신청기업은 업력 7년 이상이어야 합니다."
    items[1].text = "신청기업은 최근 매출액 100억 원을 달성해야 합니다."
    model = Model([0])
    kept, unresolved = asyncio.run(consolidate(items, [], model, scope_checked=True))
    assert kept == items and not unresolved and model.calls == 0


def test_incremental_comparison_reuses_decisions_and_preserves_new_sources():
    cache = {}
    model = Model([1])
    first = asyncio.run(
        consolidate(statements(), [], model, scope_checked=True, checked_pairs=cache)
    )
    assert len(first[0]) == 1 and model.calls == 1
    items = statements()
    items[0].source_ids.append("another-file")
    items.append(
        items[0].model_copy(
            update={
                "text": "신청기업은 업력 7년 이상이어야 합니다.",
                "source_ids": ["age"],
                "criterion": "BUSINESS_AGE",
            }
        )
    )
    kept, unresolved = asyncio.run(
        consolidate(items, [], model, scope_checked=True, checked_pairs=cache)
    )
    assert model.calls == 1 and not unresolved
    assert len(kept) == 2
    assert set(kept[0].source_ids) == {"a", "b", "another-file"}


def test_failed_pair_is_not_retried_for_each_progress_event_or_hide_unrelated_conditions():
    model = Model([99])
    cache = {}
    items = statements()
    unrelated = items[0].model_copy(
        update={
            "text": "신청기업은 업력 7년 이상이어야 합니다.",
            "source_ids": ["age"],
            "criterion": "BUSINESS_AGE",
        }
    )

    async def scenario():
        for _ in range(3):
            kept, unresolved = await consolidate(
                items + [unrelated], [], model, scope_checked=True, checked_pairs=cache
            )
            assert kept == [unrelated] and unresolved == {"a", "b"}

    asyncio.run(scenario())
    assert model.calls == 2


def test_incremental_scope_review_sends_only_new_sentences_and_remembers_exclusions():
    calls = []
    items = statements()
    items[0].text = "신청기업은 업력 7년 이상이어야 합니다."
    items[1].text = "로그인 메뉴에서 접수 버튼을 선택해야 합니다."

    class ScopeModel:
        def with_structured_output(self, *args, **kwargs):
            class Run:
                async def ainvoke(self, messages):
                    data = json.loads(messages[-1].content)
                    calls.append(data)
                    return Consolidation(
                        choices=[], out_of_scope=[i for i in data["scope_indexes"] if i == 1]
                    )

            return Run()

    async def scenario():
        pairs, scope = {}, {}
        for batch in ([items[0]], items, items):
            kept, unresolved = await consolidate(
                batch, [], ScopeModel(), checked_pairs=pairs, checked_scope=scope
            )
            assert kept == [items[0]] and not unresolved

    asyncio.run(scenario())
    assert len(calls) == 2
    assert [s["index"] for s in calls[0]["statements"]] == [0]
    assert [s["index"] for s in calls[1]["statements"]] == [1]


def test_failed_incremental_scope_retains_checked_sentences_and_does_not_repeat_calls():
    calls = []
    items = statements()
    items[0].text = "신청기업은 업력 7년 이상이어야 합니다."
    items[1].text = "신청기업은 최근 매출액 100억 원을 달성해야 합니다."

    class ScopeModel:
        def with_structured_output(self, *args, **kwargs):
            class Run:
                async def ainvoke(self, messages):
                    data = json.loads(messages[-1].content)
                    calls.append(data)
                    if 1 in data["scope_indexes"]:
                        raise TimeoutError()
                    return Consolidation(choices=[])

            return Run()

    async def scenario():
        pairs, scope = {}, {}
        await consolidate([items[0]], [], ScopeModel(), checked_pairs=pairs, checked_scope=scope)
        for _ in range(3):
            kept, unresolved = await consolidate(
                items, [], ScopeModel(), checked_pairs=pairs, checked_scope=scope
            )
            assert kept == [items[0]] and unresolved == {"b"}

    asyncio.run(scenario())
    assert len(calls) == 3
