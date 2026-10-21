import asyncio
import json
from pathlib import Path

import pytest

from app.domains.participation.consolidation import Consolidation
from app.domains.participation.preprocess import clean, preprocess
from app.domains.participation.schemas import Draft, Review, RewriteRequest, Source, Statement
from app.domains.participation.service import rewrite
from app.domains.participation.validation import validate

FIXTURE = json.loads(Path(__file__).with_name("cases.json").read_text(encoding="utf-8"))


def request(case):
    return RewriteRequest(
        sources=[Source(id=case["id"], excerpt=case["raw"], criteria=[case["criterion"]])],
        criteria=FIXTURE["catalog"],
    )


def statement(case, **changes):
    return Statement.model_validate(
        dict(
            source_ids=[case["id"]],
            criterion=case["criterion"],
            text=case["expected"] or case["raw"],
            group_label="",
            supporting_quotes=[case["raw"]],
            kind="ELIGIBILITY",
        )
        | changes
    )


class Model:
    def __init__(self, drafts, reject=False):
        self.drafts = drafts
        self.reject = reject
        self.calls = []
        self.index = 0

    def with_structured_output(self, schema, **kwargs):
        owner = self

        class Runnable:
            async def ainvoke(self, messages):
                owner.calls.append((schema, messages))
                if schema is Consolidation:
                    payload = json.loads(messages[-1].content)
                    return Consolidation(
                        choices=[
                            dict(pair=p["pair"], keep=[p["left"], p["right"]])
                            for p in payload["pairs"]
                        ]
                    )
                if schema is Draft:
                    result = owner.drafts[min(owner.index, len(owner.drafts) - 1)]
                    owner.index += 1
                    owner.last = result
                    return result
                return Review(
                    uncovered_source_ids=[],
                    verdicts=[
                        dict(
                            index=i,
                            approved=not owner.reject,
                            errors=["unsupported_fact"] if owner.reject else [],
                        )
                        for i, _ in enumerate(owner.last.statements)
                    ],
                )

        return Runnable()


@pytest.mark.parametrize("case", FIXTURE["cases"], ids=lambda c: c["id"])
def test_requested_examples_use_model_and_fail_closed(case):
    req = request(case)
    output = Draft(
        statements=[statement(case)] if case["expected"] else [],
        unrecoverable_ids=[] if case["expected"] else [case["id"]],
    )
    model = Model([output])
    result = asyncio.run(rewrite(req, model))
    assert model.calls[0][0] is Draft
    payload = json.loads(model.calls[0][1][1].content)
    assert payload["sources"][0]["excerpt"] == case["raw"]
    assert next(
        item for item in payload["catalog"] if item["code"] == payload["sources"][0]["criteria"][0]
    )["category"]
    assert "group_label" in payload["rows"][0]
    if case["expected"]:
        assert [s.text for s in result.statements] == [case["expected"]]
        assert any(schema is Review for schema, _ in model.calls)
    else:
        assert result.statements == []
        assert result.evidence_only_ids == [case["id"]]
        assert model.index == 2


def test_preprocess_preserves_tracks_headers_and_evidence():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "tiers")
    req = request(case)
    rows, types = preprocess(req)
    assert len(rows) == 3
    assert [row.group_label for row in rows] == types
    assert req.sources[0].excerpt == case["raw"]
    table = next(c for c in FIXTURE["cases"] if c["id"] == "submission_table")
    rows, _ = preprocess(request(table))
    assert len(rows) == 1
    assert rows[0].headers == ["제출 서류", "주요내용", "비고"]
    assert rows[0].cells[2] == "기관장 직인본 필수"
    assert clean("가. 상시근로자 10명 미만 ([참고1],[참고2] 참조) 나.") == "상시근로자 10명 미만"
    assert clean("① (기 업) 매출 1,000억원, 70%, C10~C34") == "(기업) 매출 1,000억원, 70%, C10~C34"


@pytest.mark.parametrize(
    "text",
    [
        "제품이 대상입니다.",
        "민간기관이 대상입니다.",
        "업력 기준은 7년 이상입니다.",
        "가. 신청기업은 매출액 100억 원이어야 합니다.",
        "신청기업은 매출액 999억 원이어야 합니다.",
        "□ 신청기업은 매출액 100억 원이어야 합니다.",
        "신청기업은 전담조직구성등신청요건중예정으로기재한사항을충족해야합니다.",
        "중소기업인 경우에는 <",
        "[참고1] 신청기업은 중소기업이어야 합니다.",
    ],
)
def test_invalid_output_is_never_suffix_repaired(text):
    case = next(c for c in FIXTURE["cases"] if c["id"] == "matching")
    model = Model([Draft(statements=[statement(case, text=text)], unrecoverable_ids=[])])
    result = asyncio.run(rewrite(request(case), model))
    assert result.statements == []
    assert result.evidence_only_ids == [case["id"]]
    assert model.index == 2
    assert len(model.calls) == 4


def test_semantic_rejection_retries_then_hides():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "matching")
    model = Model([Draft(statements=[statement(case)], unrecoverable_ids=[])], reject=True)
    assert asyncio.run(rewrite(request(case), model)).statements == []
    assert model.index == 2


def test_retry_receives_errors_and_replaces_bad_statement():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "closure")
    bad = Draft(statements=[statement(case, text="기업이 대상입니다")], unrecoverable_ids=[])
    good = Draft(statements=[statement(case)], unrecoverable_ids=[])
    model = Model([bad, good])
    assert asyncio.run(rewrite(request(case), model)).statements == good.statements
    payload = json.loads(model.calls[2][1][1].content)
    assert payload["errors"] and payload["previous_draft"]


def test_duplicate_across_criteria_and_wrong_classification():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "sanction")
    item = statement(case)
    draft = Draft(
        statements=[item, item.model_copy(update={"criterion": "REPRESENTATIVE"})],
        unrecoverable_ids=[],
    )
    errors = validate(draft, request(case), [], 2)
    assert set(errors) == {0, 1}
    assert "wrong_criterion" in errors[1]


def test_unavailable_verifier_and_limit_never_show_unverified_output():
    class Broken:
        def with_structured_output(self, *args, **kwargs):
            raise RuntimeError("private provider error")

    case = FIXTURE["cases"][3]
    result = asyncio.run(rewrite(request(case), Broken()))
    assert not result.statements
    req = request(case)
    req.sources[0].excerpt = "조건 " * 30000
    model = Model([])
    assert asyncio.run(rewrite(req, model)).status == "UNAVAILABLE"
    assert not model.calls


@pytest.mark.parametrize(
    "name,text",
    [
        ("fragment_private", "신청대상은 민간기관입니다."),
        ("fragment_policy", "신청 대상은 정책적 배려기업인 소상공인 및 소기업입니다."),
        ("unknown_tiers", "신청기업은 업력 7년 이상이어야 합니다."),
    ],
)
def test_bare_sources_cannot_be_laundered_into_plausible_paraphrases(name, text):
    case = next(c for c in FIXTURE["cases"] if c["id"] == name)
    model = Model([Draft(statements=[statement(case, text=text)], unrecoverable_ids=[])])
    assert asyncio.run(rewrite(request(case), model)).statements == []


@pytest.mark.parametrize("approved", [True, False])
def test_non_conditions_require_independent_review_before_being_omitted(approved):
    case = dict(id="schedule", raw="접수 기간: 2026년 10월 21일", criterion="PLAN", expected=None)

    class Reviewer(Model):
        def with_structured_output(self, schema, **kwargs):
            if schema is not Review:
                return super().with_structured_output(schema, **kwargs)

            class Run:
                async def ainvoke(self, messages):
                    return Review(
                        verdicts=[], uncovered_source_ids=[] if approved else ["schedule"]
                    )

            return Run()

    model = Reviewer(
        [Draft(statements=[], unrecoverable_ids=[], irrelevant_source_ids=["schedule"])]
    )
    result = asyncio.run(rewrite(request(case), model))
    assert result.irrelevant_source_ids == (["schedule"] if approved else [])
    assert result.evidence_only_ids == ([] if approved else ["schedule"])
    assert model.index == (1 if approved else 2)


def test_real_track_header_survives_enterprise_subheading():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "types")
    rows, types = preprocess(request(case))
    assert types == ["창업과제", "일반과제"]
    assert [row.group_label for row in rows] == types


def test_reported_age_revenue_mixture_is_rejected_even_if_verifier_approves():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "tiers")
    item = statement(
        case,
        text=(
            "스케일업 100억 트랙은 업력이 7년 이상이며 "
            "매출액 100억원을 달성한 중소기업이 대상입니다."
        ),
        group_label="스케일업 100억 트랙",
    )
    model = Model([Draft(statements=[item], unrecoverable_ids=[])])
    assert asyncio.run(rewrite(request(case), model)).statements == []


def test_common_conditions_are_allowed_without_inventing_track_names():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "tiers")
    item = statement(
        case,
        text="모든 유형 공통으로 신청기업은 업력 7년 이상이어야 합니다.",
        criterion="BUSINESS_AGE",
        group_label="모든 유형 공통",
    )
    assert (
        validate(
            Draft(statements=[item], unrecoverable_ids=[]),
            request(case),
            ["스케일업 100억 트랙", "스케일업 500억 트랙"],
            1,
        )
        == {}
    )


def test_new_organization_and_numeric_fact_cannot_pass_even_with_a_valid_quote():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "matching")
    item = statement(case, text="신청기업은 가상진흥원에 자부담 99%를 납부해야 합니다.")
    errors = validate(Draft(statements=[item], unrecoverable_ids=[]), request(case), [], 1)
    assert "unsupported_number" in errors[0]
    assert "unsupported_organization" in errors[0]


@pytest.mark.parametrize(
    "criterion,text",
    [
        ("EMPLOYMENT", "신청기업은 최근 90일간 고용한 종업원 수가 10% 이상이어야 합니다."),
        ("PREF_SOCIAL", "최대 5점 항목의 가점 대상은 정책적 배려기업과 소상공인 및 소기업입니다."),
    ],
)
def test_preference_cannot_become_mandatory_or_inherit_a_broken_neighbor(criterion, text):
    case = next(c for c in FIXTURE["cases"] if c["id"] == "employment")
    item = statement(case, criterion=criterion, text=text)
    assert validate(Draft(statements=[item], unrecoverable_ids=[]), request(case), [], 1)


@pytest.mark.parametrize("marker", ["○", "ㆍ", "・", "※", "□", "*", "(1)", "①", "가.", "1."])
def test_every_requested_list_marker_is_rejected_at_display_boundary(marker):
    case = next(c for c in FIXTURE["cases"] if c["id"] == "closure")
    item = statement(case, text=marker + " " + case["expected"])
    assert validate(Draft(statements=[item], unrecoverable_ids=[]), request(case), [], 1)


def test_timeout_does_not_mark_unread_rows_as_absent(monkeypatch):
    from app.domains.participation import service

    class Slow:
        def with_structured_output(self, *args, **kwargs):
            class Run:
                async def ainvoke(self, messages):
                    await asyncio.sleep(0.1)

            return Run()

    monkeypatch.setattr(service, "CALL_TIMEOUT", 0.001)
    case = FIXTURE["cases"][3]
    response = asyncio.run(rewrite(request(case), Slow()))
    assert response.status == "UNAVAILABLE"
    assert response.evidence_only_ids == [case["id"]]


def test_api_route_uses_the_typed_pipeline_without_other_analysis(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from app.domains.participation import api
    from app.domains.participation.schemas import RewriteResponse

    received = []

    async def fake(request):
        received.append(request)
        return RewriteResponse(
            statements=[], evidence_only_ids=[s.id for s in request.sources], status="READY"
        )

    monkeypatch.setattr(api, "rewrite", fake)
    app = FastAPI()
    app.include_router(api.router)
    client = TestClient(app)
    req = request(FIXTURE["cases"][0])
    result = client.post("/internal/monitoring/participation-rewrite", json=req.model_dump())
    assert result.status_code == 200
    assert result.json()["evidence_only_ids"] == [req.sources[0].id]
    assert received == [req]
    assert client.post("/internal/monitoring/participation-rewrite", json={}).status_code == 422


def test_batches_preserve_sources_merge_duplicate_facts_and_keep_partial_failures(monkeypatch):
    from app.domains.participation import service
    from app.domains.participation.schemas import RewriteResponse

    calls = []

    async def batch(req, model, on_progress=None):
        calls.append(req)
        if req.sources[0].id == "s12":
            return RewriteResponse(
                statements=[], evidence_only_ids=[s.id for s in req.sources], status="UNAVAILABLE"
            )
        return RewriteResponse(
            statements=[
                Statement(
                    source_ids=[s.id],
                    criterion="COMPANY_SIZE",
                    text="신청기업은 중소기업이어야 합니다.",
                    group_label="",
                    supporting_quotes=[s.excerpt],
                    kind="ELIGIBILITY",
                )
                for s in req.sources
            ],
            evidence_only_ids=[],
            status="READY",
        )

    monkeypatch.setattr(service, "_rewrite_batch", batch)
    req = RewriteRequest(
        sources=[
            Source(
                id=f"s{i}", excerpt="신청기업은 중소기업이어야 합니다.", criteria=["COMPANY_SIZE"]
            )
            for i in range(25)
        ],
        criteria=FIXTURE["catalog"],
    )
    result = asyncio.run(service.rewrite(req))
    assert [len(call.sources) for call in calls] == [4, 8, 8, 5]
    assert len(result.statements) == 1
    assert set(result.statements[0].source_ids) == {f"s{i}" for i in range(12)} | {
        f"s{i}" for i in range(20, 25)
    }
    assert set(result.evidence_only_ids) == {f"s{i}" for i in range(12, 20)}
    assert result.status == "READY"


def test_batch_limit_retains_unprocessed_evidence_without_calls_or_text_truncation(monkeypatch):
    from app.domains.participation import service
    from app.domains.participation.schemas import RewriteResponse

    monkeypatch.setattr(service, "MAX_BATCHES", 12)
    calls = []

    async def batch(req, model, on_progress=None):
        calls.append(req)
        return RewriteResponse(
            statements=[],
            evidence_only_ids=[],
            status="READY",
            irrelevant_source_ids=[s.id for s in req.sources],
        )

    monkeypatch.setattr(service, "_rewrite_batch", batch)
    req = RewriteRequest(
        sources=[Source(id=f"s{i}", excerpt="문의처 안내 " * 450) for i in range(20)],
        criteria=FIXTURE["catalog"],
    )
    result = asyncio.run(service.rewrite(req))
    assert len(calls) == 12
    assert result.evidence_only_ids == [f"s{i}" for i in range(12, 20)]


def test_percentage_subheaders_and_separate_tables_keep_their_own_columns():
    req = request(FIXTURE["cases"][3])
    req.sources[0].excerpt = (
        "[표]\n지원유형\t지원한도\t지원한도\n"
        "지원유형\t국비 (70%)\t자부담(30%)\n"
        "일반\t630만원 이내\t270만원 이상\n[/표]\n"
        "[표]\n업종\t업종\n금속제품 제조업 (21809)\t자동차부품 제조업 (21846)\n[/표]"
    )
    rows, _ = preprocess(req)
    assert len(rows) == 2
    assert rows[0].headers == ["지원유형", "지원한도 / 국비 (70%)", "지원한도 / 자부담(30%)"]
    assert rows[0].cells == ["일반", "630만원 이내", "270만원 이상"]
    assert rows[1].headers == ["업종", "업종"]


def test_organization_cannot_be_expanded_from_an_ambiguous_abbreviation():
    case = dict(
        id="org",
        raw="한국산업안전보건공단에서 업종 분류. 가입 코드는 공단에서 확인 예정",
        criterion="SECTOR",
        expected="가입 코드는 한국산업안전보건공단에서 확인합니다.",
    )
    item = statement(case, supporting_quotes=["가입 코드는 공단에서 확인 예정"])
    errors = validate(Draft(statements=[item], unrecoverable_ids=[]), request(case), [], 1)
    assert "unsupported_organization" in errors[0]


def test_list_cleanup_preserves_a_real_parenthetical_word_and_cleaned_scope():
    case = dict(
        id="list",
        raw="6. ①공단, ②전문기관, ③소공인, ④공급기업간 4자계약 협조",
        criterion="CONSORTIUM",
        expected="신청기업은 공단, 전문기관, 공급기업과의 4자계약에 협조해야 합니다.",
    )
    item = statement(case, group_label="공단, 전문기관, 소공인, 공급기업간 4자계약 협조")
    assert validate(Draft(statements=[item], unrecoverable_ids=[]), request(case), [], 1) == {}
    case = dict(
        id="duplicate",
        raw="당해 연도 유사사업(지자체 등) 수혜자의 경우 중복지원 불가",
        criterion="DUPLICATE",
        expected="당해 연도 유사사업(지자체 등) 수혜자는 중복지원받을 수 없습니다.",
    )
    assert (
        validate(Draft(statements=[statement(case)], unrecoverable_ids=[]), request(case), [], 1)
        == {}
    )


def test_participation_model_settings_do_not_inherit_other_sections(monkeypatch):
    from app.domains.participation.config import RewriteSettings

    monkeypatch.delenv("PARTICIPATION_REWRITE_MODEL", raising=False)
    monkeypatch.delenv("PARTICIPATION_REWRITE_REASONING_EFFORT", raising=False)
    monkeypatch.setenv("REPORT_BRIEF_MODEL", "unrelated-model")
    settings = RewriteSettings.from_env()
    assert settings.model == "gpt-5.4"
    assert settings.reasoning_effort == "low"


def test_group_list_must_contain_only_real_source_tracks():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "types")
    item = statement(
        case,
        criterion="COMPANY_SIZE",
        text="창업과제와 일반과제에 신청하는 기업은 판로지원법상 중소기업자에 해당해야 합니다.",
        group_label="창업과제, 일반과제",
    )
    req = request(case)
    types = preprocess(req)[1]
    assert validate(Draft(statements=[item], unrecoverable_ids=[]), req, types, 2) == {}
    bad = item.model_copy(update={"group_label": "창업과제, 가상과제"})
    assert (
        "unsupported_group"
        in validate(Draft(statements=[bad], unrecoverable_ids=[]), req, types, 2)[0]
    )


def test_exact_same_fact_under_different_headings_keeps_one_sentence_and_both_sources():
    from app.domains.participation.service import merge_identical

    case = next(c for c in FIXTURE["cases"] if c["id"] == "closure")
    first = statement(case, source_ids=["s1"], group_label="신청 제한")
    second = first.model_copy(update={"source_ids": ["s2"], "group_label": "휴폐업"})
    merged = merge_identical([first, second])
    assert len(merged) == 1
    assert merged[0].text == first.text
    assert merged[0].source_ids == ["s1", "s2"]


def test_task_mentions_are_not_invented_application_tracks():
    req = request(FIXTURE["cases"][3])
    req.sources[0].excerpt = (
        "중복과제 및 유사과제는 제외합니다. "
        "정부지원과제의 보안과제·안전과제·위탁과제를 확인합니다. "
        "R&D자율성트랙 신청 안내를 참고합니다."
    )
    assert preprocess(req)[1] == []
    req.sources[
        0
    ].excerpt = "바. 지원내용 : 안전환경(일반), 안전환경(고위험업종) 중 1개 항목 선택지원"
    assert preprocess(req)[1] == ["안전환경(일반)", "안전환경(고위험업종)"]


def test_waiting_for_a_model_slot_is_also_bounded(monkeypatch):
    from app.domains.participation import service

    monkeypatch.setattr(service, "_semaphore", asyncio.Semaphore(0))
    monkeypatch.setattr(service, "TOTAL_TIMEOUT", 0.001)
    model = Model([])
    result = asyncio.run(service.rewrite(request(FIXTURE["cases"][0]), model))
    assert result.status == "UNAVAILABLE" and not model.calls


def test_empty_table_cells_do_not_shift_the_meaning_of_amount_columns():
    req = request(FIXTURE["cases"][3])
    req.sources[0].excerpt = "[표]\n지원유형\t국비\t자부담\n일반\t\t270만원 이상\n[/표]"
    rows, _ = preprocess(req)
    assert rows[0].headers == ["지원유형", "국비", "자부담"]
    assert rows[0].cells == ["일반", "", "270만원 이상"]
    req.sources[0].excerpt = "[표]\n지원유형\t국비\t자부담\n일반\t630만원 이내\t\n[/표]"
    assert preprocess(req)[0][0].cells == ["일반", "630만원 이내", ""]


def test_verified_progress_arrives_before_slow_batch_and_survives_deadline(monkeypatch):
    from app.domains.participation import service
    from app.domains.participation.schemas import RewriteResponse

    async def scenario():
        case = next(c for c in FIXTURE["cases"] if c["id"] == "closure")
        req = request(case)
        req.sources = [req.sources[0].model_copy(update={"id": f"s{i}"}) for i in range(13)]
        item = statement(case, source_ids=["s0"])
        emitted = []

        async def batch(partial, model, on_progress=None):
            if partial.sources[0].id != "s0":
                await asyncio.sleep(10)
            return RewriteResponse(statements=[item], evidence_only_ids=[], status="READY")

        async def checked(result, *args):
            return result

        async def progress(result):
            assert result.statements == [item]
            assert "s12" in result.evidence_only_ids
            emitted.append(result)

        monkeypatch.setattr(service, "_rewrite_batch", batch)
        monkeypatch.setattr(service, "_consolidate_response", checked)
        monkeypatch.setattr(service, "DOCUMENT_TIMEOUT", 0.05)
        result = await service.rewrite(req, on_progress=progress)
        assert emitted and result.statements == [item]
        assert result.status == "READY"

    asyncio.run(scenario())


def test_stream_endpoint_emits_progress_and_terminal_snapshot(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from app.domains.participation import api
    from app.domains.participation.schemas import RewriteResponse

    case = next(c for c in FIXTURE["cases"] if c["id"] == "closure")
    result = RewriteResponse(statements=[statement(case)], evidence_only_ids=[], status="READY")

    async def fake(req, on_progress):
        await on_progress(result)
        return result

    monkeypatch.setattr(api, "rewrite", fake)
    app = FastAPI()
    app.include_router(api.router)
    response = TestClient(app).post(
        "/internal/monitoring/participation-rewrite/stream", json=request(case).model_dump()
    )
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [event["done"] for event in events] == [False, True]
    assert all(event["result"]["statements"][0]["text"] == case["expected"] for event in events)


def test_retry_outage_retains_sentences_that_already_passed_independent_review():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "closure")
    req = request(case)
    req.sources.append(Source(id="other", excerpt="깨진 표 원문"))

    class RetryOutage:
        attempts = 0

        def with_structured_output(self, schema, **kwargs):
            owner = self

            class Run:
                async def ainvoke(self, messages):
                    if schema is Draft:
                        owner.attempts += 1
                        if owner.attempts == 2:
                            raise TimeoutError()
                        return Draft(statements=[statement(case)], unrecoverable_ids=["other"])
                    return Review(
                        verdicts=[dict(index=0, approved=True, errors=[])], uncovered_source_ids=[]
                    )

            return Run()

    result = asyncio.run(rewrite(req, RetryOutage()))
    assert result.statements == [statement(case)]
    assert result.status == "READY"
    assert "other" in result.evidence_only_ids


def test_first_verified_conditions_publish_before_retry_and_only_failed_source_is_rewritten():
    from app.domains.participation import service

    good = next(c for c in FIXTURE["cases"] if c["id"] == "closure")
    retry = next(c for c in FIXTURE["cases"] if c["id"] == "matching")
    req = request(good)
    req.sources += request(retry).sources
    published = []
    payloads = []

    class Targeted:
        def with_structured_output(self, schema, **kwargs):
            class Run:
                async def ainvoke(self, messages):
                    data = json.loads(messages[-1].content)
                    if schema is Draft:
                        payloads.append(data)
                        if len(payloads) == 1:
                            return Draft(
                                statements=[statement(good)], unrecoverable_ids=[retry["id"]]
                            )
                        # This runs while the document consumer can publish the first review.
                        await asyncio.sleep(0.01)
                        assert published and published[0].statements == [statement(good)]
                        return Draft(statements=[statement(retry)], unrecoverable_ids=[])
                    if schema is Consolidation:
                        return Consolidation(choices=[])
                    assert schema is Review
                    return Review(
                        verdicts=[dict(index=0, approved=True, errors=[])], uncovered_source_ids=[]
                    )

            return Run()

    async def scenario():
        async def progress(result):
            published.append(result)

        return await service.rewrite(req, Targeted(), progress)

    result = asyncio.run(scenario())
    assert len(payloads) == 2
    assert [s["id"] for s in payloads[1]["sources"]] == [retry["id"]]
    assert [r["source_id"] for r in payloads[1]["rows"]] == [retry["id"]]
    assert payloads[1]["accepted_statements"][0]["text"] == good["expected"]
    assert {s.text for s in result.statements} == {good["expected"], retry["expected"]}
    assert result.evidence_only_ids == []


def test_scope_rejection_in_independent_review_never_reaches_display():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "closure")

    class Scope(Model):
        def with_structured_output(self, schema, **kwargs):
            if schema is not Review:
                return super().with_structured_output(schema, **kwargs)

            class Run:
                async def ainvoke(self, messages):
                    return Review(
                        verdicts=[dict(index=0, approved=False, errors=["out_of_scope"])],
                        uncovered_source_ids=[],
                    )

            return Run()

    model = Scope([Draft(statements=[statement(case)], unrecoverable_ids=[])])
    assert not asyncio.run(rewrite(request(case), model)).statements
    assert model.index == 2


def test_empty_retry_does_not_claim_remaining_conditions_of_partial_source_are_complete():
    case = next(c for c in FIXTURE["cases"] if c["id"] == "closure")
    model = Model(
        [
            Draft(statements=[statement(case)], unrecoverable_ids=[case["id"]]),
            Draft(statements=[], unrecoverable_ids=[]),
        ]
    )
    result = asyncio.run(rewrite(request(case), model))
    assert result.statements == [statement(case)]
    assert result.evidence_only_ids == [case["id"]]
