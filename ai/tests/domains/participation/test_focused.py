import asyncio
import json

import httpx
import pytest

from app.core.openai_models import ChatOpenAI
from app.domains.participation import focused, service
from app.domains.participation.focused import ExtractedCondition, Extraction
from app.domains.participation.schemas import Criterion, RewriteRequest, Source


def request(*sources):
    return RewriteRequest(
        criteria=[
            Criterion(code=code, label=code, category="참여기업 조건") for code in focused.CODES
        ],
        sources=list(sources),
    )


def source(sid, text, heading="신청자격"):
    return Source(id=sid, excerpt=text, group_label=heading)


def item(sid, code, quote, kind="ELIGIBILITY", basis="신청자격"):
    return ExtractedCondition(
        source_id=sid,
        criterion=code,
        quote=quote,
        basis=basis,
        kind=kind,
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


def test_four_fields_required_and_preferred_are_visible_with_one_call():
    age = "공고일 기준 창업 후 7년 이내인 기업"
    revenue = "직전년도 매출액 10억원 이상 50억원 미만인 기업"
    patent = "관련 분야 등록 특허 보유기업 가점 2점. 단, 출원 중인 특허는 인정하지 않음."
    sector = "자동차 부품 제조업을 주된 사업으로 영위하는 기업"
    req = request(
        source("a", age), source("r", revenue), source("p", patent, "우대사항"), source("s", sector)
    )
    model = Fake(
        [
            item("a", "BUSINESS_AGE", age),
            item("r", "REVENUE", revenue),
            item("p", "IP", patent, "PREFERENCE", "우대사항"),
            item("s", "SECTOR", sector),
        ]
    )
    result = asyncio.run(service.rewrite(req, model))
    assert len(model.calls) == 1
    assert [(s.criterion, s.kind, s.text) for s in result.statements] == [
        ("BUSINESS_AGE", "ELIGIBILITY", age),
        ("REVENUE", "ELIGIBILITY", revenue),
        ("IP", "PREFERENCE", patent),
        ("SECTOR", "ELIGIBILITY", sector),
    ]
    assert not result.evidence_only_ids


def test_revenue_preference_is_allowed_in_revenue_field():
    quote = "최근 3년 매출 증가율 20% 이상 기업에 가점 1점"
    model = Fake([item("r", "REVENUE", quote, "PREFERENCE", "우대사항")])
    result = asyncio.run(service.rewrite(request(source("r", quote, "우대사항")), model))
    assert result.statements[0].criterion == "REVENUE"
    assert result.statements[0].kind == "PREFERENCE"


def test_or_conditions_keep_whole_alternative_and_source_whitespace():
    quote = "다음 중 하나를 충족: 업력 3년 이내 또는 매출액 5억원 이하인 기업"
    raw = quote.replace("업력 3년", "업력\n  3년")
    model = Fake([item("or", "BUSINESS_AGE", quote), item("or", "REVENUE", quote)])
    result = asyncio.run(service.rewrite(request(source("or", raw)), model))
    assert [s.text for s in result.statements] == [raw, raw]
    assert all(s.supporting_quotes == [raw] for s in result.statements)


@pytest.mark.parametrize(
    ("quote", "basis", "kind"),
    [
        ("매출액 10억원 이상인 기업", "신청자격", "ELIGIBILITY"),
        ("매출액 5억원 이하인 기업", "없는 필수 제목", "ELIGIBILITY"),
        ("매출액 5억원 이하인 기업 가점 1점", "신청자격", "ELIGIBILITY"),
        ("매출액 5억원 이하인 기업은 가점 미적용", "가점", "PREFERENCE"),
        ("선정 후 매출액에 따라 기술료를 납부해야 합니다.", "신청자격", "ELIGIBILITY"),
    ],
)
def test_unverified_or_misclassified_condition_stays_as_evidence(quote, basis, kind):
    raw = quote if ("가점" in quote or "선정 후" in quote) else "매출액 5억원 이하인 기업"
    model = Fake([item("r", "REVENUE", quote, kind, basis)])
    heading = "기업 조건" if basis == "없는 필수 제목" else "신청자격"
    result = asyncio.run(service.rewrite(request(source("r", raw, heading)), model))
    assert not result.statements
    assert result.evidence_only_ids == ["r"]
    assert len(model.calls) == 1


def test_valid_item_survives_rejected_item_and_partial_source_stays_visible():
    quote = "공고일 기준 업력 7년 이내인 기업"
    model = Fake(
        [item("a", "BUSINESS_AGE", quote), item("a", "BUSINESS_AGE", "업력 10년 이상인 기업")],
        unresolved=["a"],
    )
    result = asyncio.run(service.rewrite(request(source("a", quote)), model))
    assert len(result.statements) == 1
    assert result.statements[0].text == quote
    assert result.evidence_only_ids == ["a"]


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


def test_no_related_source_uses_no_api_request():
    model = Fake()
    result = asyncio.run(
        service.rewrite(
            request(source("x", "세금 체납이 없는 중소기업")),
            model,
        )
    )
    assert not model.calls
    assert result.evidence_only_ids == ["x"]


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


@pytest.mark.parametrize("quote", ["업력 무관", "제조업체", "특허 보유"])
def test_short_source_conditions_are_not_rejected_for_sentence_style(quote):
    code = "BUSINESS_AGE" if "업력" in quote else "IP" if "특허" in quote else "SECTOR"
    model = Fake([item("x", code, quote, basis="모집대상")])
    result = asyncio.run(service.rewrite(request(source("x", quote, "모집대상")), model))
    assert result.statements[0].text == quote


@pytest.mark.parametrize(
    "quote",
    [
        "특허 보유 기업은 우대하지 않습니다",
        "특허 보유 기업에 가점을 부여하지 않습니다",
        "특허 보유 기업은 가점 대상이 아님",
    ],
)
def test_negated_preference_does_not_become_a_benefit(quote):
    model = Fake([item("x", "IP", quote, "PREFERENCE", "우대사항")])
    result = asyncio.run(service.rewrite(request(source("x", quote, "우대사항")), model))
    assert not result.statements
    assert result.evidence_only_ids == ["x"]


@pytest.mark.parametrize(
    "quote",
    [
        "서울 소재 기업",
        "한국산업기술진흥원에 신청서 제출",
    ],
)
def test_location_or_agency_name_does_not_become_an_industry(quote):
    model = Fake([item("x", "SECTOR", quote)])
    result = asyncio.run(service.rewrite(request(source("x", quote)), model))
    assert not result.statements
    assert result.evidence_only_ids == ["x"]


@pytest.mark.parametrize(
    ("raw", "quote", "heading", "kind"),
    [
        ("등록 특허 보유 기업 우대", "등록 특허 보유 기업", "신청자격", "ELIGIBILITY"),
        ("특허 보유 기업은 우대하지 않습니다", "특허 보유 기업", "우대사항", "PREFERENCE"),
        ("업력 7년 이상 기업", "업력 7년 이상 기업", "지원제외", "ELIGIBILITY"),
    ],
)
def test_quote_cannot_hide_preference_negation_or_exclusion(raw, quote, heading, kind):
    code = "BUSINESS_AGE" if "업력" in quote else "IP"
    model = Fake([item("x", code, quote, kind, heading)])
    result = asyncio.run(service.rewrite(request(source("x", raw, heading)), model))
    assert not result.statements
    assert result.evidence_only_ids == ["x"]


def test_explicit_exclusion_preserves_its_negative_meaning():
    quote = "업력 7년 이상 기업은 지원에서 제외"
    model = Fake([item("x", "BUSINESS_AGE", quote, basis="지원제외")])
    result = asyncio.run(service.rewrite(request(source("x", quote, "지원제외")), model))
    assert result.statements[0].text == quote


def test_recruitment_context_does_not_classify_every_source_as_business_age():
    heading = "[공고 대상] 창업기업 별도 모집\n[원문 구역] 특허 우대"
    assert focused.source_fields(source("x", "특허 보유 기업 가점 2점", heading)) == {"IP"}


@pytest.mark.parametrize("quote", ["AI기업", "ICT 서비스 기업"])
def test_named_technology_industry_is_kept(quote):
    model = Fake([item("x", "SECTOR", quote)])
    result = asyncio.run(service.rewrite(request(source("x", quote)), model))
    assert result.statements[0].text == quote


@pytest.mark.parametrize(
    ("quote", "code"),
    [
        ("사업자등록 후 1년 이상 기업", "BUSINESS_AGE"),
        ("회사성립일로부터 7년 이내 기업", "BUSINESS_AGE"),
        ("조선해양업을 영위하는 기업", "SECTOR"),
        ("소프트웨어 개발 기업", "SECTOR"),
    ],
)
def test_backend_topic_variants_are_not_dropped_by_the_ai_selector(quote, code):
    model = Fake([item("x", code, quote)])
    result = asyncio.run(service.rewrite(request(source("x", quote)), model))
    assert len(model.calls) == 1
    assert result.statements[0].text == quote


@pytest.mark.parametrize(
    "quote",
    [
        "□ (요건검토) 신청서 및 제출서류 등을 확인하여 신청자격 등 검토\n"
        "* 중소기업 여부, 휴폐업·체납 여부, 신용정보, 보조금법 위반, 부적절 업종 등",
        "신청자격 검토: 중소기업 여부, 부적합한 업종 여부, 국세 체납 여부",
        "신청자격 검토: 지원 제외 업종 여부와 지방세 체납 여부 확인",
        "동일 분야 지원사업을 수행 중인 기업은 신청 제외",
    ],
)
def test_generic_industry_screening_does_not_use_an_api_call(quote):
    model = Fake([item("x", "SECTOR", quote)])
    result = asyncio.run(service.rewrite(request(source("x", quote)), model))
    assert not model.calls
    assert not result.statements
    assert result.evidence_only_ids == ["x"]


@pytest.mark.parametrize(
    "quote",
    [
        "□ (요건검토) 신청서 및 제출서류 등을 확인하여 신청자격 등 검토\n"
        "* 중소기업 여부, 휴폐업·체납 여부, 신용정보, 보조금법 위반, 부적절 업종 등",
        "□ (제외대상) 아래 지원제외 대상에 해당하는 기업\n"
        "① 도약프로그램 지원사업을 수행 중인 기업\n"
        "② 초격차 스타트업 1000+ 지원사업을 수행 중인 기업\n"
        "③ 월드클래스300 플러스 지원사업을 수행 중인 기업\n"
        "④ 신청일 기준 휴·폐업 중인 자(기업)\n"
        "⑤ 국세 또는 지방세를 체납 중인 자(기업)",
        "신청기업은 세금 체납이 없어야 합니다",
    ],
)
def test_sector_basis_cannot_launder_general_checks_or_exclusion_lists(quote):
    heading = "유관산업 신청자격"
    model = Fake([item("x", "SECTOR", quote, basis=heading)])
    result = asyncio.run(service.rewrite(request(source("x", quote, heading)), model))
    assert len(model.calls) == (0 if not focused.source_fields(source("x", quote, heading)) else 1)
    assert not result.statements
    assert result.evidence_only_ids == ["x"]


@pytest.mark.parametrize(
    "quote",
    [
        "제조업 C10~C34에 해당하는 기업",
        "한국표준산업분류 C10~C34 해당기업",
        "관련 산업의 실적을 보유한 기업",
        "바이오 산업 관련 실적 2건 이상 보유 기업",
        "AI 분야 기술 개발 실적이 있는 기업",
        "조선해양업을 영위하는 기업",
        "신청자격 검토 결과 제조업 C10~C34에 해당하는 기업이어야 함",
        "지원 제외 업종: 유흥주점업. 해당 업종은 지원에서 제외",
        "지원 제외 업종: 유흥주점업, 도박업",
        "지원 제외 업종은 유흥주점업과 도박업",
    ],
)
def test_direct_industry_requirements_survive_screening_filter(quote):
    model = Fake([item("x", "SECTOR", quote)])
    result = asyncio.run(service.rewrite(request(source("x", quote)), model))
    assert len(model.calls) == 1
    assert result.statements[0].text == quote
    assert result.statements[0].supporting_quotes == [quote]


def test_specific_industry_clause_can_be_selected_without_neighboring_tax_list():
    direct = "바이오 산업 연구개발 실적 2건 이상인 기업"
    text = direct + "\n□ (요건검토) 체납·휴폐업·부적절 업종 등 확인"
    model = Fake([item("x", "SECTOR", direct)])
    result = asyncio.run(service.rewrite(request(source("x", text)), model))
    assert [statement.text for statement in result.statements] == [direct]


def test_focus_prompt_does_not_increase_fixed_input_budget():
    assert len(focused.PROMPT.encode("utf-8")) <= 2729


def test_readable_sentence_and_exact_evidence_are_returned_in_the_same_call():
    quote = "제조업(C10~C34)을 주된 사업으로 영위중이어야 함"
    text = "제조업(C10~C34)을 주된 사업으로 영위해야 합니다."
    selected = item("x", "SECTOR", quote).model_copy(update={"text": text})
    model = Fake([selected])
    result = asyncio.run(service.rewrite(request(source("x", quote)), model))
    assert len(model.calls) == 1
    assert result.statements[0].text == text
    assert result.statements[0].supporting_quotes == [quote]


@pytest.mark.parametrize(
    ("quote", "text", "code"),
    [
        ("업력 7년 이내 기업", "업력이 10년 이내여야 합니다.", "BUSINESS_AGE"),
        ("매출액 100억원 이상 기업", "매출액이 100만원 이상이어야 합니다.", "REVENUE"),
        (
            "매출액 10억원 이상 50억원 미만",
            "매출액은 50억원 이상 10억원 미만이어야 합니다.",
            "REVENUE",
        ),
        (
            "업력 3년 이내 또는 매출액 5억원 이하",
            "업력 3년 이내와 매출액 5억원 이하를 모두 충족해야 합니다.",
            "BUSINESS_AGE",
        ),
        ("등록 특허 보유 기업", "출원 특허를 보유해야 합니다.", "IP"),
        ("제조업을 주된 사업으로 영위", "제조업", "SECTOR"),
        ("제조업을 주된 사업으로 영위", "[표] 제조업을 주된 사업으로 영위해야 합니다.", "SECTOR"),
    ],
)
def test_summary_cannot_change_facts_logic_patent_status_or_return_a_raw_table(quote, text, code):
    model = Fake([item("x", code, quote).model_copy(update={"text": text})])
    result = asyncio.run(service.rewrite(request(source("x", quote)), model))
    assert len(result.statements) == 1
    sentence = result.statements[0].text
    assert sentence.endswith("합니다.")
    assert sentence != quote
    assert focused.valid_sentence(sentence, quote, code)
    assert result.statements[0].supporting_quotes == [quote]
    assert text not in [s.text for s in result.statements]
    assert not result.evidence_only_ids
    assert len(model.calls) == 1


def test_actual_clean_notice_documents_never_spend_a_model_call():
    from pathlib import Path

    documents = Path("../backend/src/test/resources/submission-documents/clean.txt").read_text(
        encoding="utf-8"
    )
    req = request(source("x", documents, "마. 신청서류"))
    model = Fake()
    result = asyncio.run(service.rewrite(req, model))
    assert not model.calls
    assert not result.statements


def test_mislabelled_document_table_cannot_pass_the_local_validator():
    quote = "필수 서류 소공인 클린제조환경조성 사업 신청서 자가진단 체크리스트"
    req = request(source("x", quote, "신청자격"))
    bad = item("x", "SECTOR", quote).model_copy(update={"text": "제조업을 영위해야 합니다."})
    result = focused.project(Extraction(items=[bad], unresolved_source_ids=[]), req.sources, req)
    assert not result.statements


def test_actual_condition_cell_is_allowed_while_the_whole_clean_table_is_rejected():
    from pathlib import Path

    raw = Path(
        "../backend/src/test/resources/participation-conditions/clean-structured.txt"
    ).read_text(encoding="utf-8")
    quote = "’제조업(업종코드 : C10~C34)‘을 주된 사업으로 영위중이어야 함"
    text = "제조업(C10~C34)을 주된 사업으로 영위해야 합니다."
    src = source("x", raw, "가. 신청대상")
    req = request(src)
    good = item("x", "SECTOR", quote, basis="신청대상").model_copy(update={"text": text})
    assert (
        focused.project(Extraction(items=[good], unresolved_source_ids=[]), [src], req)
        .statements[0]
        .text
        == text
    )
    block = raw[raw.index("가. 신청대상") : raw.index("1. “도시형소공인”")].strip()
    bad = item("x", "SECTOR", block, basis="신청대상")
    assert not focused.project(
        Extraction(items=[bad], unresolved_source_ids=[]), [src], req
    ).statements


def test_preference_heading_can_ground_a_complete_preference_sentence():
    quote = "등록 특허 보유 기업"
    src = source("x", quote, "우대사항")
    statement = item("x", "IP", quote, kind="PREFERENCE", basis="우대사항")
    statement = statement.model_copy(update={"text": "등록 특허 보유 기업을 우대합니다."})
    result = focused.project(
        Extraction(items=[statement], unresolved_source_ids=[]), [src], request(src)
    )
    assert result.statements[0].text == "등록 특허 보유 기업을 우대합니다."


def test_readable_sentence_cannot_drop_explicit_negation():
    assert not focused.valid_sentence("등록 특허가 있어야 합니다.", "등록 특허가 없어야 함", "IP")


@pytest.mark.parametrize(
    "code,quote,text",
    [
        (
            "BUSINESS_FORM",
            "공공기관에 해당하는 법인 또는 단체",
            "공공기관에 해당하는 법인 또는 단체여야 합니다.",
        ),
        (
            "CREDIT",
            "일반기업의 경우 기업신용평가등급 B 이상",
            "일반기업의 경우 기업신용평가등급 B 이상이어야 합니다.",
        ),
        (
            "EXPERIENCE",
            "최근 1년간 공공기관 계약실적 10억원 이하",
            "최근 1년간 공공기관 계약실적은 10억원 이하여야 합니다.",
        ),
        (
            "IP",
            "우수연구개발 기술개발제품 12종",
            "우수연구개발 기술개발제품 12종에 해당해야 합니다.",
        ),
        (
            "SECTOR",
            "경제안보품목 및 경제안보서비스의 도입 생산 및 제공에 기여하고자 하는 사업자 또는 사업자단체",  # noqa: E501
            "경제안보품목 및 경제안보서비스의 도입 생산 및 제공에 기여하고자 하는 사업자 또는 사업자단체여야 합니다.",  # noqa: E501
        ),
        (
            "REVENUE",
            "전년도 직접수출액 5,000만불 미만의 중소기업",
            "전년도 직접수출액이 5,000만불 미만인 중소기업이어야 합니다.",
        ),
    ],
)
def test_extended_eligibility_is_selected_and_grounded_in_one_call(code, quote, text):
    src = source("x", quote, "신청자격")
    candidate = item("x", code, quote).model_copy(update={"text": text})
    model = Fake([candidate])
    result = asyncio.run(service.rewrite(request(src), model))
    assert len(model.calls) == 1
    assert result.statements[0].text == text
    assert result.statements[0].supporting_quotes == [quote]


def test_mixed_purpose_section_does_not_hide_a_later_qualification():
    src = source("x", "□ 사업목적 중소기업 성장 지원\n□ 지원대상 업력 7년 이상", "사업개요")
    assert not focused.document_section(src)
    assert "BUSINESS_AGE" in focused.source_fields(src)


def test_simple_age_revenue_conjunction_can_be_split_but_not_or_or_exception():
    quote = "업력 7년 이상의 매출액 500억원 달성 중소기업"
    assert focused.valid_sentence("업력 7년 이상이어야 합니다.", quote, "BUSINESS_AGE")
    assert focused.valid_sentence("매출액 500억원을 달성해야 합니다.", quote, "REVENUE")
    assert not focused.valid_sentence(
        "업력 7년 이상이어야 합니다.", quote + " 또는 특허 보유 기업", "BUSINESS_AGE"
    )
    assert not focused.valid_sentence(
        "업력 7년 이상이어야 합니다.", quote + " 단, 추천기업 제외", "BUSINESS_AGE"
    )


def test_grade_currency_and_export_exception_cannot_change():
    assert not focused.valid_sentence(
        "기업신용평가등급 A 이상이어야 합니다.", "기업신용평가등급 B 이상", "CREDIT"
    )
    assert not focused.valid_sentence(
        "직접수출액 5,000만원 미만이어야 합니다.", "직접수출액 5,000만불 미만", "REVENUE"
    )
    quote = "전년도 직접수출액 5,000만불 미만. 단, 글로벌 강소기업 1000+ 지정기업은 5,000만불 이상도 지원 가능"  # noqa: E501
    assert not focused.valid_sentence(
        "전년도 직접수출액 5,000만불 미만이어야 합니다.", quote, "REVENUE"
    )
    text = "전년도 직접수출액은 5,000만불 미만이어야 합니다. 단, 글로벌 강소기업 1000+ 지정기업은 5,000만불 이상도 신청 가능합니다."  # noqa: E501
    assert focused.valid_sentence(text, quote, "REVENUE")


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


def test_real_heading_grounds_condition_when_model_basis_is_not_verbatim():
    quote = "매출액 5억원 이하인 기업"
    model = Fake([item("r", "REVENUE", quote, basis="필수 신청 요건")])
    result = asyncio.run(service.rewrite(request(source("r", quote, "지원 대상")), model))
    assert [s.text for s in result.statements] == [quote]
    assert len(model.calls) == 1


def test_heading_fallback_cannot_turn_preference_into_mandatory():
    quote = "매출액 5억원 이하인 기업에 가점 1점"
    model = Fake([item("r", "REVENUE", quote, basis="필수 신청 요건")])
    result = asyncio.run(service.rewrite(request(source("r", quote)), model))
    assert not result.statements


def test_multi_item_response_survives_formatting_and_display_failures_without_retry(caplog):
    raw = "업력 7년 이상 중소기업\n매출액 100억원 이상인 기업"
    model = Fake(
        [
            item("r", "BUSINESS_AGE", "업력7년이상 중소기업").model_copy(
                update={"text": "업력이 10년 이상이어야 합니다."}
            ),
            item("r", "REVENUE", "매출액100억원이상인 기업").model_copy(
                update={"text": "매출액이 100만원 이상이어야 합니다."}
            ),
            item("r", "REVENUE", "매출액500억원이상인 기업"),
        ]
    )
    with caplog.at_level("INFO", logger=focused.__name__):
        result = asyncio.run(service.rewrite(request(source("r", raw)), model))
    assert [s.text for s in result.statements] == [
        line + "이어야 합니다." for line in raw.splitlines()
    ]
    assert len(model.calls) == 1
    assert result.evidence_only_ids == ["r"]
    assert "candidates=3 accepted=2 local_sentence=2 rejected=1" in caplog.text
    assert "QUOTE_NOT_IN_SOURCE" in caplog.text
    assert raw not in caplog.text
    assert "100만원" not in caplog.text


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


@pytest.mark.parametrize(
    "quote",
    [
        "신용정보 요건 충족 여부가 포함될 수 있다.",
        "신청자격 및 방법\n신청자격 : 공공기관\n신청기간 : 상시모집\n신청방법 : 온라인 신청",
    ],
)
def test_unreadable_or_speculative_summary_is_never_replaced_with_raw_block(quote):
    code = "CREDIT" if "신용" in quote else "BUSINESS_FORM"
    model = Fake([item("x", code, quote).model_copy(update={"text": quote})])
    result = asyncio.run(service.rewrite(request(source("x", quote)), model))
    assert not result.statements
    assert len(model.calls) == 1
    assert result.evidence_only_ids == ["x"]


def test_mixed_source_can_yield_a_single_qualification_sentence_in_one_call():
    quote = "판로지원법 제2조 제2호에 따른 공공기관"
    raw = "신청자격 및 방법\n신청자격 : " + quote + "\n신청기간 : 상시모집\n신청방법 : 온라인 신청"
    text = quote + "이어야 합니다."
    model = Fake([item("x", "BUSINESS_FORM", quote).model_copy(update={"text": text})])
    result = asyncio.run(service.rewrite(request(source("x", raw)), model))
    assert [s.text for s in result.statements] == [text]
    assert result.statements[0].supporting_quotes == [quote]
    assert len(model.calls) == 1


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


def test_explicit_secondary_buyer_is_excluded_but_buyer_recruitment_is_allowed():
    raw = "□ 신청자격\n공공기관만 신청 가능"
    primary = source("primary", raw, "[상위 구역] 구매기관 현황 및 신청\n□ 신청자격")
    secondary = source(
        "secondary", raw, "[별도 참여 역할] 구매기관\n[상위 구역] 구매기관 현황 및 신청\n□ 신청자격"
    )
    assert [s.id for s in focused.select_sources(request(primary, secondary))] == ["primary"]


def test_operator_recruitment_does_not_include_separate_beneficiary_age():
    context = "[공고 대상] 주관기관 모집, 창업기업 모집은 별도 공고 예정\n"
    operator = source(
        "operator",
        "□ 신청 자격\n전문 기관 또는 법인이 신청 가능",
        "[상위 구역] 신청자격 및 제외대상\n" + context + "[원문 구역] □ 신청 자격",
    )
    beneficiary = source(
        "beneficiary",
        "□ 지원대상 : 업력 10년 이내 기업",
        "[상위 구역] 사업 개요\n" + context + "[원문 구역] □ 지원대상",
    )
    assert [s.id for s in focused.select_sources(request(operator, beneficiary))] == ["operator"]


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


def test_markets_and_associations_are_applicant_types_not_industries():
    for raw in ["전통시장 및 상점가", "상인회 또는 협동조합", "소공인"]:
        assert "BUSINESS_FORM" in focused.source_fields(source("x", "지원대상 " + raw))


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
    if notice_id == 1:
        assert "s14" not in {s.id for s in selected}
    if notice_id == 7:
        assert "업력 10년" not in combined
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


@pytest.mark.parametrize("code", ["BUSINESS_AGE", "REVENUE"])
def test_decorated_compound_clause_recovers_a_valid_field_sentence_without_a_retry(code):
    quote = "□ (지원대상) 업력 7년 이상의 매출액 500억원 달성* 중소기업"
    text = focused.source_sentence(quote, code, "ELIGIBILITY")
    assert text
    assert focused.valid_sentence(text, quote, code)
    assert "□" not in text and "*" not in text
    if code == "BUSINESS_AGE":
        assert "500" not in text
    else:
        assert "7년" not in text


def test_nested_actual_heading_can_ground_a_mandatory_sector_condition():
    quote = "경제안보품목의 도입·생산에 기여하는 사업자단체"
    req = request(source("x", "\U000f02b1 선정 대상 및 방법\nㅇ (대상) " + quote, ""))
    extraction = Extraction(
        items=[item("x", "SECTOR", quote, basis="지원 자격")], unresolved_source_ids=[]
    )
    result = focused.project(extraction, focused.select_sources(req), req)
    assert len(result.statements) == 1


def test_clause_cleanup_preserves_multiplication_and_only_removes_footnote_stars():
    assert focused.clean_clause("매출액* 2 ≥ 30인 기업") == "매출액* 2 ≥ 30인 기업"
    assert focused.clean_clause("□ (지원대상) 매출액 20억원 달성* 중소기업") == (
        "매출액 20억원 달성 중소기업"
    )


def test_clause_cleanup_removes_footnote_before_korean_particle():
    assert focused.clean_clause("매출액 30억원 미만*의 기업") == "매출액 30억원 미만의 기업"


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
    candidate = item("a", "BUSINESS_FORM", "L2-L3", basis="L1")
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
            items=[item("a", "BUSINESS_FORM", reference, basis="H0")], unresolved_source_ids=[]
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
            items=[item("a", "BUSINESS_FORM", "L2-L5", basis="L1")], unresolved_source_ids=[]
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
    candidate = item("a", "BUSINESS_FORM", "L2: 다른 대기업이라고 잘못 재출력한 설명", basis="H0")
    result = focused.project(
        Extraction(items=[candidate], unresolved_source_ids=[]), [src], request(src)
    )
    assert result.statements[0].supporting_quotes == ["중소기업"]
    assert "대기업" not in result.statements[0].text


def test_research_sentence_recovery_preserves_all_deadlines_and_exception():
    quote = (
        "◦주관연구개발기관이 기업인 경우는 접수마감일 현재 법인사업자이어야 하며, "
        "선정평가 개최일 이전에 기업부설연구소(한국산업기술진흥협회 인정서 기준)를 "
        "보유하고 있어야 함(연구개발전담부서는 불인정)"
    )
    result = focused.source_sentence(quote, "BUSINESS_FORM", "ELIGIBILITY")
    assert result == (
        "주관연구개발기관이 기업인 경우, 접수마감일 현재 법인사업자여야 하며, "
        "선정평가 개최일 이전에 기업부설연구소(한국산업기술진흥협회 인정서 기준)를 "
        "보유해야 합니다. 연구개발전담부서는 인정되지 않습니다."
    )
    assert (
        focused.source_sentence(quote.replace("불인정", "인정"), "BUSINESS_FORM", "ELIGIBILITY")
        is None
    )


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


def test_bad_model_prose_can_recover_a_complete_obligation_without_an_extra_call():
    quote = "주관기관이 기업인 경우 법인사업자이어야 함"
    model = Fake(
        [item("a", "BUSINESS_FORM", quote).model_copy(update={"text": "법인 조건 확인 필요"})]
    )
    result = asyncio.run(service.rewrite(request(source("a", quote)), model))
    assert len(model.calls) == 1
    assert result.statements[0].text == "주관기관이 기업인 경우 법인사업자이어야 합니다."
    assert result.statements[0].supporting_quotes == [quote]


def test_law_summary_does_not_drop_an_actual_age_limit():
    quote = "「중소기업기본법」 제2조에 따른 업력 7년 이하 중소기업"
    sentence = focused.source_sentence(quote, "BUSINESS_AGE", "ELIGIBILITY")
    assert sentence == "업력 7년 이하 중소기업이어야 합니다."
    assert focused.valid_sentence(sentence, quote, "BUSINESS_AGE")
    assert not focused.valid_sentence("중소기업이어야 합니다.", quote, "BUSINESS_FORM")
