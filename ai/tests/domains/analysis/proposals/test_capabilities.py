import asyncio
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.domains.analysis.context.company_profile import BISTELLIGENCE_PROFILE
from app.domains.analysis.proposals.capabilities import company_capability_catalog
from app.domains.analysis.proposals.drafting import (
    LangChainProposalGenerationRunner,
    ProposalModelOutput,
    _parse_model_response,
)
from tests.domains.analysis.proposals.test_drafting import ProposalRunner, document, result


def model_payload(matches):
    draft = asyncio.run(ProposalRunner().generate(document(), result()))
    payload = draft.model_dump()
    payload["preparation"]["strategy"]["capability_matches"] = matches
    return payload


def test_model_selects_company_ids_and_never_authors_company_facts():
    schema = ProposalModelOutput.model_json_schema()["$defs"]["ProposalCapabilityModelOutput"]
    assert set(schema["properties"]) == {"companyEvidenceId", "strategicInterpretation"}
    assert set(schema["required"]) == {"companyEvidenceId", "strategicInterpretation"}


def test_catalog_excludes_unknown_relationships_and_future_project_ideas():
    profile = replace(
        BISTELLIGENCE_PROFILE,
        unknown_fields=("미확인 고객사와 계약을 맺었습니다.",),
        relevant_project_types=("아직 수행하지 않은 지향 과제",),
        case_studies=("2026년 7월 기준 제조 설비 AI 개발 중. 성과 수치는 미공개",),
    )
    catalog = company_capability_catalog(profile)
    assert "개발 중" in catalog["case:1"] and "미공개" in catalog["case:1"]
    assert "미확인 고객사" not in " ".join(catalog.values())
    assert "지향 과제" not in " ".join(catalog.values())


def test_generated_notice_requirement_cannot_replace_selected_company_fact():
    catalog = company_capability_catalog(BISTELLIGENCE_PROFILE)
    payload = model_payload([{
        "company_evidence_id": "service:1",
        "confirmed_fact": "제출서류로 최근 3개년 결산재무제표 제출을 요구합니다.",
        "strategic_interpretation": "제조 AI 에이전트 설계 경험을 실증 과업의 설계에 활용합니다.",
    }])
    restored, _ = _parse_model_response(payload, catalog)
    card = restored.preparation.strategy.capability_matches[0]
    assert card.company_evidence_id == "service:1"
    assert card.confirmed_fact == catalog["service:1"]
    assert "결산재무제표" not in card.confirmed_fact


def test_invalid_and_duplicate_sources_are_removed_while_valid_work_is_retained():
    action = "회사의 제조 AI 개발 서비스를 공고의 실증 과업에 활용합니다."
    payload = model_payload([
        {"company_evidence_id": key, "strategic_interpretation": action}
        for key in ["NOTICE_BODY", "service:1", "service:1", "unknownFields:1"]
    ])
    restored, _ = _parse_model_response(payload, company_capability_catalog(BISTELLIGENCE_PROFILE))
    matches = restored.preparation.strategy.capability_matches
    assert [item.company_evidence_id for item in matches] == ["service:1"]
    assert restored.preparation.eligibility_checklist
    assert restored.preparation.submission_documents


def test_no_matching_company_evidence_does_not_invent_a_capability():
    payload = model_payload([{
        "company_evidence_id": "notice:1",
        "strategic_interpretation": "공고의 높은 평가 배점을 회사의 수행 실적으로 제시합니다.",
    }])
    restored, _ = _parse_model_response(payload, company_capability_catalog(BISTELLIGENCE_PROFILE))
    assert restored.preparation.strategy.capability_matches == []
    payload = model_payload([])
    restored, _ = _parse_model_response(payload, {})
    assert restored.preparation.strategy.capability_matches == []


def test_runner_uses_its_input_catalog_and_preserves_other_proposal_sections():
    async def scenario():
        draft = await ProposalRunner().generate(document(), result())
        draft.preparation.strategy.capability_matches[0].confirmed_fact = (
            "선정평가에서 인턴십 운영기업의 우수성이 높은 배점을 차지합니다."
        )
        runner = LangChainProposalGenerationRunner.__new__(LangChainProposalGenerationRunner)
        runner._settings = SimpleNamespace(max_text_chars=20_000, proposal_timeout_seconds=5)
        runner._draft_model = SimpleNamespace(ainvoke=AsyncMock(return_value=draft))
        generated = await runner.generate(document(), result())
        prompt = runner._draft_model.ainvoke.call_args.args[0]
        assert "회사 역량·사례 선택 목록" in prompt and '"service:1"' in prompt
        assert "confirmedFact는 코드가 회사 목록에서 복사" in prompt
        assert generated.preparation.strategy.capability_matches[0].confirmed_fact == (
            company_capability_catalog(BISTELLIGENCE_PROFILE)["service:1"]
        )
        assert generated.preparation.eligibility_checklist
        assert generated.preparation.submission_documents
        runner._draft_model.ainvoke.assert_awaited_once()

    asyncio.run(scenario())
