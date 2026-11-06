import asyncio
import json

import httpx
import pytest

from app.core.openai_models import ChatOpenAI
from app.domains.participation import focused, service
from app.domains.participation.focused import ExtractedCondition, Extraction
from app.domains.participation.schemas import Criterion, RewriteRequest, Source, Statement


def request(*sources):
    return RewriteRequest(
        criteria=[
            Criterion(code=code, label=code, category="참여기업 조건") for code in focused.CODES
        ],
        sources=list(sources),
    )


def source(sid, text, heading="신청자격"):
    return Source(id=sid, excerpt=text, group_label=heading)


def item(sid, code, quote, kind="ELIGIBILITY", text=None, scope=None, audience=None):
    return ExtractedCondition(
        source_id=sid,
        criterion=code,
        quote=quote,
        kind=kind,
        text=quote if text is None else text,
        scope=scope,
        audience=audience,
    )


class Fake:
    def __init__(self, items=(), error=None, unresolved=()):
        self.result = Extraction(items=list(items), unresolved_source_ids=list(unresolved))
        self.calls = []
        self.error = error

    def with_structured_output(self, schema, **kwargs):
        owner = self

        class Run:
            async def ainvoke(self, messages, **kwargs):
                owner.calls.append((schema, messages))
                if owner.error:
                    raise owner.error
                return owner.result

        return Run()


def test_field_balancing_does_not_spend_every_slot_on_age():
    sources = [source(str(i), f"창업 후 {i + 1}년 이내 기업") for i in range(20)]
    sources += [
        source("r", "매출액 5억원 미만 기업"),
        source("p", "특허 보유기업"),
        source("s", "자동차 제조업 영위기업"),
    ]
    selected = focused.select_sources(request(*sources))
    assert len(selected) == 12
    assert {"r", "p", "s"} <= {s.id for s in selected}


def test_unselected_whole_source_remains_evidence():
    huge = source("huge", "특허 보유 조건 " * 1500)
    model = Fake()
    result = asyncio.run(service.rewrite(request(huge), model))
    assert not model.calls
    assert result.evidence_only_ids == ["huge"]


def test_failure_does_not_retry_or_show_generated_text():
    model = Fake(error=RuntimeError("private-provider-body"))
    result = asyncio.run(
        service.rewrite(
            request(source("a", "창업 후 7년 이내인 기업")),
            model,
        )
    )
    assert len(model.calls) == 1
    assert result.status == "UNAVAILABLE"
    assert not result.statements
    assert result.evidence_only_ids == ["a"]


def test_input_guard_stops_before_api_call():
    model = Fake()
    req = request(source("a", "창업 후 7년 이내인 기업", "신청자격" * 8000))
    result = asyncio.run(service.rewrite(req, model))
    assert not model.calls
    assert result.status == "UNAVAILABLE"


def test_sdk_single_request_cheap_model_caps_and_usage_log(monkeypatch, caplog):
    monkeypatch.setenv("OPENAI_API_KEY", "test-not-real")
    monkeypatch.delenv("PARTICIPATION_REWRITE_MODEL", raising=False)
    monkeypatch.delenv("PARTICIPATION_REWRITE_REASONING_EFFORT", raising=False)
    monkeypatch.setattr("app.domains.participation.config.load_dotenv", lambda *_: None)
    quote = "공고일 기준 창업 후 7년 이내인 기업"
    payloads, options = [], []

    async def scenario():
        def respond(req):
            payloads.append(json.loads(req.content))
            answer = Extraction(
                items=[item("a", "BUSINESS_AGE", quote)],
                unresolved_source_ids=[],
            )
            return httpx.Response(
                200,
                json={
                    "id": "chatcmpl-focused-test",
                    "object": "chat.completion",
                    "created": 0,
                    "model": "gpt-5-nano",
                    "choices": [
                        {
                            "index": 0,
                            "finish_reason": "stop",
                            "message": {"role": "assistant", "content": answer.model_dump_json()},
                        }
                    ],
                    "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150},
                },
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:

            def factory(**kwargs):
                options.append(kwargs)
                return ChatOpenAI(**kwargs, http_async_client=client)

            monkeypatch.setattr(focused, "ChatOpenAI", factory)
            return await service.rewrite(request(source("a", quote)))

    with caplog.at_level("INFO", logger=focused.__name__):
        result = asyncio.run(scenario())
    assert result.statements[0].text == quote
    assert len(payloads) == 1
    assert payloads[0]["model"] == "gpt-5-nano"
    assert payloads[0]["reasoning_effort"] == "minimal"
    assert payloads[0]["max_completion_tokens"] == 2048
    assert options[0]["max_retries"] == 0
    assert '"input_tokens": 120' in caplog.text
    assert quote not in caplog.text and "test-not-real" not in caplog.text


def test_extended_budget_does_not_grow_and_qualification_gets_priority():
    noise = [source(str(i), "산업 분야 소개 " * 20, "기관 소개") for i in range(15)]
    key = source("export", "지원대상 전년도 직접수출액 5,000만불 미만", "지원대상")
    selected = focused.select_sources(request(*noise, key))
    assert any(s.id == "export" for s in selected)
    assert sum(len(s.excerpt.encode("utf-8")) for s in selected) <= 8000
    assert focused.MAX_OUTPUT_TOKENS <= 2048
    assert len(selected) <= 12


def test_actual_operator_and_export_fixture_qualifications_fit_the_reduced_input_budget():
    import re
    from pathlib import Path

    folder = Path("../backend/src/test/resources/participation-conditions")
    operator = (folder / "startup-structured.txt").read_text(encoding="utf-8")
    certification = (folder / "certification-hwpx.txt").read_text(encoding="utf-8")
    for body, marker, expected in [
        (operator, "□ 신청 자격", {"BUSINESS_FORM", "EXPERIENCE", "CREDIT"}),
        (certification, "□ 지원대상", {"REVENUE", "BUSINESS_FORM"}),
    ]:
        start = body.index(marker)
        block = re.split(r"\n□", body[start:], maxsplit=1)[0]
        src = source("real", block, marker)
        selected = focused.select_sources(request(src))
        assert len(selected) == 1
        assert len(selected[0].excerpt.encode("utf-8")) <= 8000
        assert expected <= focused.source_fields(selected[0])


def test_expanded_serialized_input_guard_stops_before_any_model_request():
    src = source("x", "업력 7년 이상", "신청자격 " + "x" * 20_000)
    model = Fake()
    result = asyncio.run(service.rewrite(request(src), model))
    assert result.status == "UNAVAILABLE"
    assert not model.calls
    assert focused.MAX_INPUT_BYTES == 20_000


@pytest.mark.parametrize(
    "normalized",
    [
        "중소기업기본법 제2조에 따른 중소기업",
        "｢중소기업기본법｣ 제 2 조에 따른 중소기업",
    ],
)
def test_quote_formatting_is_restored_to_actual_source(normalized):
    raw = "「중소기업기본법」 제 2조에 따른 중소기업"
    restored = focused.original_quote(normalized, raw)
    assert restored in raw
    assert "제 2조" in restored
    assert "제2조" not in restored


@pytest.mark.parametrize(
    "fabricated",
    [
        "매출액 500억원 이상",
        "매출액 100억원 이하",
        "매출액 100억원 > 0",
        "매출액 100억원 이상 또는 특허 보유",
    ],
)
def test_quote_normalization_does_not_fuzz_facts(fabricated):
    assert focused.original_quote(fabricated, "매출액 100억원 이상") is None


def test_restored_oversized_quote_does_not_erase_other_valid_items(caplog):
    raw = "업력" + " " * 610 + "7년 이상"
    good = "매출액 100억원 이상인 기업"
    req = request(source("a", raw), source("r", good))
    extraction = Extraction(
        items=[
            item("a", "BUSINESS_AGE", "업력7년 이상"),
            item("r", "REVENUE", good),
        ],
        unresolved_source_ids=[],
    )
    with caplog.at_level("INFO", logger=focused.__name__):
        result = focused.project(extraction, req.sources, req)
    assert [s.text for s in result.statements] == [good]
    assert "QUOTE_TOO_LONG" in caplog.text


@pytest.mark.parametrize("labels", [("성장형", "도약형", "확장형"), ("A트랙", "B트랙", "C트랙")])
def test_all_qualification_scopes_precede_large_other_sections(labels):
    noise = source("noise", "중소기업의 선정 후 의무 " * 130, "선정 후 의무")
    sources = [noise] + [
        source(
            str(i),
            f"□ 지원대상\n업력 6년 이상이며 매출액 {i + 2}억원 이상인 기업",
            f"[적용 유형] {label}\n□ 지원대상",
        )
        for i, label in enumerate(labels)
    ]
    selected = focused.select_sources(request(*sources))
    assert {s.id for s in selected} == {"0", "1", "2"}
    assert sum(len(s.excerpt.encode()) for s in selected) <= 8000


def test_nested_qualification_retains_wrapped_sentence_table_and_exception():
    condition = "□ 신청자격\n가공업을 주된 사업으로 영위하는\n상시근로자 10인 미만 소공인\n"
    condition += (
        "[표]\n유형\t조건\n가. 유형\t특허 등록 기업 또는 인증 기업\n[/표]\n※ 단, 예외기업 포함\n"
    )
    raw = "□ 사업목적\n중소기업 지원\n" + condition + "□ 제출서류\n매출액 증명서 제출"
    selected = focused.select_sources(request(source("nested", raw, "사업개요")))
    assert len(selected) == 1
    assert selected[0].excerpt == condition


def test_astral_pdf_section_bullets_do_not_force_sending_the_entire_document():
    raw = "공고 안내\n" + "지원 절차 설명 " * 800
    condition = (
        "\U000f02b1 선정 대상 및 방법\nㅇ (대상) 경제안보품목을 생산하는 사업자 또는 사업자단체\n"
    )
    raw += "\n" + condition + "\U000f02b2 신청 절차\n신청서 제출"
    selected = focused.select_sources(request(source("pdf", raw, "")))
    assert len(selected) == 1
    assert selected[0].excerpt == condition


def test_same_condition_for_distinct_named_tracks_is_not_dropped():
    raw = "□ 지원대상\n업력 6년 이상인 기업"
    selected = focused.select_sources(
        request(
            *[
                source(str(i), raw, f"[적용 유형] {name}\n□ 지원대상")
                for i, name in enumerate(["성장형", "확장형"])
            ]
        )
    )
    assert len(selected) == 2


def test_large_attachment_collection_still_uses_one_bounded_call_for_short_qualification():
    noise = [source(str(i), "인증 목록 " * 1500, "첨부 목록") for i in range(8)]
    key = source("key", "□ 신청자격\n전년도 직접수출액 4,000만불 미만인 중소기업")
    req = request(*noise, key)
    assert sum(len(s.excerpt) for s in req.sources) > 64000
    fake = Fake([item("key", "REVENUE", "전년도 직접수출액 4,000만불 미만인 중소기업")])
    result = asyncio.run(service.rewrite(req, fake))
    assert result.status == "READY"
    assert len(result.statements) == 1
    assert len(fake.calls) == 1
    schema, messages = fake.calls[0]
    assert focused.CallBudget().reserve(messages, schema) <= focused.MAX_INPUT_BYTES
    assert len(json.loads(messages[1].content)["sources"]) == 1


@pytest.mark.parametrize("notice_id", range(1, 8))
def test_real_public_notice_sections_fit_one_request_and_keep_expected_eligibility(notice_id):
    from pathlib import Path

    corpus = json.loads((Path(__file__).parent / "fixtures/notices.json").read_text("utf-8"))
    notice = next(row for row in corpus if row["id"] == notice_id)
    req = request(*[Source.model_validate(row) for row in notice["sources"]])
    selected = focused.select_sources(req)
    combined = "\n".join(s.excerpt for s in selected)
    expected = {
        1: ["창업과제", "일반과제", "소액과제", "딥테크과제"],
        2: ["C10~C34", "10인 미만"],
        3: ["전통시장", "상점가", "상권활성화구역"],
        4: ["경제안보품목", "사업자 또는 사업자단체"],
        5: ["100억원", "500억원", "1,000억원"],
        6: ["직접수출액 5,000만불 미만", "글로벌 강소기업"],
        7: ["전문 기관", "공공기관", "민간기관"],
    }
    for clause in expected[notice_id]:
        assert "".join(clause.split()) in "".join(combined.split())
    if notice_id == 5:
        headings = "\n".join(s.group_label for s in selected)
        assert all(label in headings for label in ["점프업100", "점프업500", "점프업1000"])
    fake = Fake()
    result = asyncio.run(service.rewrite(req, fake))
    assert result.status == "READY"
    assert len(fake.calls) == 1
    schema, messages = fake.calls[0]
    assert focused.CallBudget().reserve(messages, schema) <= 20000
    assert sum(len(s.excerpt.encode()) for s in selected) <= 8000
    assert len(selected) <= 12


def test_synthetic_join_between_qualification_sections_cannot_ground_a_quote():
    raw = (
        "□ 신청자격\n업력 6년 이상 기업\n□ 제출서류\n신청서 제출\n"
        "□ 지원대상\n매출액 2억원 이상 기업"
    )
    req = request(source("x", raw))
    selected = focused.select_sources(req)
    forged = selected[0].excerpt
    assert "[다음 원문 구간]" in forged
    extraction = Extraction(items=[item("x", "BUSINESS_AGE", forged)], unresolved_source_ids=[])
    assert focused.project(extraction, selected, req).statements == []


def test_adjacent_qualification_chunks_keep_the_original_contiguous_quote():
    text = "□ 신청자격\n중소기업\n□ 지원대상\n업력 7년 이내 창업기업"
    selected = focused.select_sources(request(source("a", text, "공고")))
    assert len(selected) == 1
    assert selected[0].excerpt == text
    assert "[다음 원문 구간]" not in selected[0].excerpt


@pytest.mark.parametrize("heading", ["입찰보증금", "과업수행계획", "평가항목", "계약 체결 후"])
def test_post_contract_and_bid_deposit_sections_never_consume_a_model_call(heading):
    model = Fake()
    result = asyncio.run(
        service.rewrite(request(source("a", "중소기업의 매출액과 수행실적 제출", heading)), model)
    )
    assert not model.calls
    assert not result.statements


def test_numbered_qualification_parent_reaches_selection_without_form_lists():
    req = request(
        source(
            "a",
            "접수마감일 현재 법인사업자이어야 함",
            "[상위 구역] 신청자격\n4-2. 신청자격 / □ 주관연구개발기관",
        ),
        source("b", "기업부설연구소 및 중소기업 확인서 제출", "8. 제출서류"),
    )
    assert [s.id for s in focused.select_sources(req)] == ["a"]


def test_rewritten_law_alias_in_quote_is_still_rejected():
    original = "「전통시장 및 상점가 육성을 위한 특별법」(이하 전통시장법) 제2조에 따른 전통시장"
    changed = "「전통시장 및 상점가 육성을 위한 특별법」 제2조에 따른 전통시장"
    model = Fake([item("a", "BUSINESS_FORM", changed)])
    result = asyncio.run(service.rewrite(request(source("a", original)), model))
    assert not result.statements


def test_line_references_restore_exact_original_without_retyping_law_or_exception():
    text = "□ 신청자격\r\n중소기업기본법 제2조에 따른 중소기업\r\n※ 단, 대기업은 제외"
    src = source("a", text)
    candidate = item(
        "a", "BUSINESS_FORM", "L2-L3",
        text="중소기업기본법 제2조에 따른 중소기업이 지원 대상입니다. 단, 대기업은 제외됩니다.",
    )
    result = focused.project(
        Extraction(items=[candidate], unresolved_source_ids=[]), [src], request(src)
    )
    assert result.statements[0].supporting_quotes == [text.split("\r\n", 1)[1]]
    messages = focused.model_messages([src])
    numbered = json.loads(messages[1].content)["sources"][0]["excerpt"]
    assert (
        numbered
        == "L1: □ 신청자격\nL2: 중소기업기본법 제2조에 따른 중소기업\nL3: ※ 단, 대기업은 제외"
    )


@pytest.mark.parametrize("reference", ["L0", "L9", "L3-L2", "L2,L3", "L2-L99"])
def test_invalid_line_reference_is_never_accepted(reference):
    src = source("a", "□ 신청자격\n중소기업")
    result = focused.project(
        Extraction(
            items=[item("a", "BUSINESS_FORM", reference)], unresolved_source_ids=[]
        ),
        [src],
        request(src),
    )
    assert not result.statements


def test_references_cannot_cross_synthetic_source_gap():
    raw = "□ 신청자격\n중소기업\n□ 지원기간\n2026년\n□ 지원대상\n업력 7년 이내 기업"
    req = request(source("a", raw, "공고"))
    selected = focused.select_sources(req)
    result = focused.project(
        Extraction(
            items=[item("a", "BUSINESS_FORM", "L2-L5")], unresolved_source_ids=[]
        ),
        selected,
        req,
    )
    assert not result.statements


def test_direct_eligibility_is_not_starved_by_large_target_and_financial_sections():
    req = request(
        source("targets", "□ 지원대상 목록\n중소기업 제조업 " + "대상 품목 " * 180, "지원대상"),
        source(
            "credit", "업력 기업 부채비율 매출액 " * 110, "[상위 구역] 신청자격\n1. 기업의 부도"
        ),
        source(
            "core",
            "◦ 주관연구개발기관이 기업인 경우 법인사업자이어야 함",
            "4-2. 신청자격 / □ 주관연구개발기관",
        ),
    )
    selected = focused.select_sources(req)
    assert focused.selection.prepare(req.sources[-1], focused.source_fields)[1] == 0
    assert focused.selection.prepare(req.sources[0], focused.source_fields)[1] == 1
    assert "core" in [s.id for s in selected]
    assert sum(len(s.excerpt.encode()) for s in selected) <= 8000


def test_line_prefix_with_model_comment_only_uses_the_referenced_original():
    src = source("a", "□ 신청자격\n중소기업")
    candidate = item(
        "a", "BUSINESS_FORM", "L2: 다른 대기업이라고 잘못 재출력한 설명", text="중소기업입니다."
    )
    result = focused.project(
        Extraction(items=[candidate], unresolved_source_ids=[]), [src], request(src)
    )
    assert result.statements[0].supporting_quotes == ["중소기업"]
    assert "대기업" not in result.statements[0].text


def test_nested_eligibility_survives_a_coarse_business_purpose_heading():
    src = source("a", "□ 사업목적\n기업 성장 지원\n□ 신청자격\n중소기업", "사업목적")
    chosen = focused.select_sources(request(src))
    assert len(chosen) == 1
    assert chosen[0].excerpt == "□ 신청자격\n중소기업"


def test_whitespace_only_evidence_never_reaches_model():
    model = Fake()
    result = asyncio.run(service.rewrite(request(source("a", "\n  ")), model))
    assert not model.calls
    assert not result.statements


def test_model_sentence_scope_role_and_exception_are_preserved_without_a_second_call():
    raw = "□ 신청자격\n주관기관은 지원역량을 보유한 대학 또는 연구기관. 단, 부설기관은 제외"
    text = "지원역량을 보유한 대학 또는 연구기관이 신청할 수 있습니다. 단, 부설기관은 제외됩니다."
    candidate = item(
        "one", "BUSINESS_FORM", "L2", text=text, scope="성장형", audience="주관기관"
    )
    model = Fake([candidate])
    result = asyncio.run(service.rewrite(request(source("one", raw)), model))
    assert len(model.calls) == 1
    assert len(result.statements) == 1
    statement = result.statements[0]
    assert (statement.text, statement.scope, statement.audience) == (text, "성장형", "주관기관")
    assert statement.supporting_quotes == [raw.split("\n", 1)[1]]


@pytest.mark.parametrize("text", [None, "", " \n\t "])
def test_empty_model_text_is_not_completed_from_source(text):
    src = source("one", "업력 7년 이내 기업")
    candidate = item("one", "BUSINESS_AGE", "L1").model_copy(update={"text": text})
    result = focused.project(
        Extraction(items=[candidate], unresolved_source_ids=[]), [src], request(src)
    )
    assert not result.statements
    assert result.evidence_only_ids == ["one"]


@pytest.mark.parametrize(
    "text,code,kind",
    [
        ("독립적으로 보육 프로그램을 운영할 수 있어야 합니다.", "EXPERIENCE", "ELIGIBILITY"),
        ("대학의 부설 조직도 신청 가능", "BUSINESS_FORM", "ELIGIBILITY"),
        ("해당 기술을 보유하면 평가에 2점을 더합니다.", "IP", "PREFERENCE"),
        ("매출이 있어도 신청할 수 있습니다.", "BUSINESS_AGE", "ELIGIBILITY"),
    ],
)
def test_meaning_and_sentence_style_are_not_reclassified_by_code(text, code, kind):
    # This test establishes the boundary: grounding does not prove semantic correctness.
    src = source("one", "원문에 명시된 신청 요건")
    candidate = item("one", code, "L1", kind, text=text)
    result = focused.project(
        Extraction(items=[candidate], unresolved_source_ids=[]), [src], request(src)
    )
    assert [statement.text for statement in result.statements] == [text]
    assert result.statements[0].criterion == code
    assert result.statements[0].kind == kind


def test_invalid_source_or_quote_does_not_drop_valid_neighbor_and_logs_no_content(caplog):
    src = source("private-source-id", "대학 또는 연구기관이 신청 가능")
    good = item(src.id, "BUSINESS_FORM", "L1", text="대학 또는 연구기관이 신청할 수 있습니다.")
    bad_quote = item(src.id, "BUSINESS_FORM", "존재하지 않는 비공개 원문")
    bad_source = item("unknown-id", "BUSINESS_FORM", "L1", text="모델 원문 비공개")
    empty = good.model_copy(update={"text": None})
    extraction = Extraction(items=[good, bad_quote, bad_source, empty], unresolved_source_ids=[])
    with caplog.at_level("INFO", logger=focused.__name__):
        result = focused.project(extraction, [src], request(src))
    assert len(result.statements) == 1
    assert result.evidence_only_ids == [src.id]
    assert "candidates=4 accepted=1 rejected=3" in caplog.text
    for reason in ("QUOTE_NOT_IN_SOURCE", "SOURCE_NOT_SELECTED", "EMPTY_TEXT"):
        assert reason in caplog.text
    for private in (src.id, src.excerpt, good.text, bad_quote.quote, bad_source.text):
        assert private not in caplog.text


def test_unfamiliar_terms_in_explicit_qualification_are_selected():
    src = source("new", "□ 신청자격\n자체 거점을 두고 육성 프로그램을 운영할 수 있는 조직")
    assert focused.source_fields(src) == set()
    assert focused.select_sources(request(src)) == [src]


def test_direct_qualification_does_not_spend_remaining_budget_on_other_roles():
    primary = source("main", "□ 신청자격\n공공기관", "신청자격")
    secondary = source(
        "buyer", "□ 신청자격\n공공기관", "[별도 참여 역할] 구매기관\n신청자격"
    )
    chosen = focused.select_sources(request(primary, secondary))
    assert [row.id for row in chosen] == ["main"]
    # When it is the only recruitment context, the model still receives the role.
    secondary_only = focused.select_sources(request(secondary))
    assert len(secondary_only) == 1
    assert "[별도 참여 역할] 구매기관" in secondary_only[0].group_label


def test_missing_scope_stays_unspecified_instead_of_using_section_title():
    src = source("one", "공공기관과 대학이 신청 가능", "신청자격 및 신청요건")
    result = focused.project(
        Extraction(items=[item(src.id, "BUSINESS_FORM", "L1")], unresolved_source_ids=[]),
        [src], request(src),
    )
    assert result.statements[0].scope is None
    assert result.statements[0].audience is None


def test_legacy_statement_without_scope_or_audience_remains_readable():
    value = {
        "source_ids": ["one"], "criterion": "BUSINESS_FORM", "text": "중소기업입니다.",
        "group_label": "신청자격", "supporting_quotes": ["중소기업"], "kind": "ELIGIBILITY",
    }
    statement = Statement.model_validate(value)
    assert statement.scope is None and statement.audience is None


@pytest.mark.parametrize("field,value", [("criterion", "INVENTED"), ("kind", "FUNDING")])
def test_structure_enum_is_still_enforced(field, value):
    from pydantic import ValidationError

    value_dict = item("one", "BUSINESS_FORM", "L1").model_dump()
    value_dict[field] = value
    with pytest.raises(ValidationError):
        ExtractedCondition.model_validate(value_dict)


def test_cost_limits_and_prompt_remain_within_the_previous_budget():
    assert len(focused.PROMPT.encode("utf-8")) <= 2729
    assert focused.MAX_SOURCE_BYTES == 8000
    assert focused.MAX_INPUT_BYTES == 20000
    assert focused.MAX_OUTPUT_TOKENS == 2048
    assert focused.MAX_SOURCES == 12
    assert focused.TIMEOUT_SECONDS == 45
    assert focused.DOCUMENT_TIMEOUT_SECONDS == 60
    assert "basis" not in ExtractedCondition.model_json_schema()["properties"]
    assert Extraction.model_json_schema()["properties"]["items"]["maxItems"] == 12


def test_empty_extraction_has_no_local_generated_conditions():
    model = Fake()
    result = asyncio.run(
        service.rewrite(request(source("one", "공공기관과 대학이 신청 가능")), model)
    )
    assert len(model.calls) == 1
    assert result.statements == []
    assert result.evidence_only_ids == ["one"]


def test_zero_sources_make_no_paid_request():
    model = Fake()
    result = asyncio.run(service.rewrite(request(), model))
    assert not model.calls
    assert result.status == "READY"
    assert result.statements == []


def test_selection_log_reports_counts_without_original_or_identifier(caplog):
    src = source("private-source-id", "대학이 신청할 수 있습니다.")
    with caplog.at_level("INFO", logger=focused.__name__):
        asyncio.run(service.rewrite(request(src), Fake()))
    assert "candidates=1 selected=1 unselected=0" in caplog.text
    assert src.id not in caplog.text and src.excerpt not in caplog.text


def test_serialized_prompt_source_and_schema_input_is_smaller_than_previous_contract():
    selected = [source("one", "□ 신청자격\n업력 7년 이내 기업")]
    # Baseline measured with the previous prompt/schema and these exact source fields.
    previous_input_bytes = 4082
    actual = focused.CallBudget().reserve(focused.model_messages(selected), Extraction)
    assert actual < previous_input_bytes
    assert actual <= focused.MAX_INPUT_BYTES


def test_paid_input_omits_unused_rule_classifications():
    selected = [source("one", "대학이 신청할 수 있습니다.").model_copy(update={"criteria": ["IP"]})]
    payload = json.loads(focused.model_messages(selected)[1].content)
    assert set(payload["sources"][0]) == {"id", "group_label", "excerpt"}
