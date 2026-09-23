import asyncio
from dataclasses import replace
from unittest.mock import AsyncMock, patch

from app.domains.analysis.company_profile import BISTELLIGENCE_PROFILE
from app.domains.analysis.legal_pair import (
    LegalPairRequest,
    LegalPairReviewer,
    validate_pair_output,
)


def request(excerpt="동일 과제 중복 지원은 금지합니다.", verified=True):
    side = dict(
        purpose="반도체 공정 개선과 불량 탐지",
        eligibility="제조 기업이 대상입니다.",
        requiredPartner="원문 확인이 필요합니다.",
    )
    return LegalPairRequest.model_validate(
        dict(
            current=side,
            similar=side,
            checks=[
                dict(
                    type="DUPLICATE_SUPPORT",
                    status="RESTRICTION_FOUND",
                    finding="한쪽 공고에 제한 조항이 있습니다.",
                    evidence=excerpt,
                    verifiedEvidence=[
                        dict(
                            side="현재 공고",
                            interpretation="동일 과제 지원 제한입니다.",
                            excerpt=excerpt,
                        )
                    ]
                    if verified
                    else [],
                )
            ],
        )
    )


def output(ref=1, kind="DUPLICATE_SUPPORT"):
    return dict(
        insights=[
            dict(
                type=kind,
                comparison="사업 목적에 반도체 공정 개선이 공통으로 포함됩니다.",
                implication="실제 수행 범위가 동일하면 중복지원 제한 적용 여부를 확인해야 합니다.",
                verification="두 신청 과제의 목표와 수행 범위를 대조합니다.",
                evidenceIds=[ref],
            )
        ]
    )


def test_pair_rejects_invented_or_wrong_type_evidence():
    assert validate_pair_output(output(99), request()).status == "UNAVAILABLE"
    assert validate_pair_output(output(kind="CONFIDENTIALITY"), request()).status == "UNAVAILABLE"
    assert validate_pair_output(output(), request()).status == "COMPLETED"


def test_unverified_fallback_excerpt_never_triggers_pair_model():
    reviewer = LegalPairReviewer()
    with patch.object(reviewer, "_generate_and_cache", new_callable=AsyncMock) as generate:
        result = asyncio.run(reviewer.review(request(verified=False)))
        assert result.status == "NEEDS_EVIDENCE"
        generate.assert_not_awaited()


def test_pair_cache_and_concurrent_requests_share_one_call_and_changed_evidence_invalidates():
    async def scenario():
        reviewer = LegalPairReviewer()
        model = AsyncMock()

        async def invoke(prompt):
            await asyncio.sleep(0)
            return output()

        model.ainvoke.side_effect = invoke
        with (
            patch("app.domains.analysis.legal_pair.AnalysisSettings.from_env") as settings,
            patch("app.domains.analysis.legal_pair.ChatOpenAI") as factory,
        ):
            settings.return_value.model_name = "test-model"
            settings.return_value.api_key = "test-key"
            factory.return_value.with_structured_output.return_value = model
            first, second = await asyncio.gather(
                reviewer.review(request()), reviewer.review(request())
            )
            assert first == second
            assert (await reviewer.review(request())).status == "COMPLETED"
            assert model.ainvoke.await_count == 1
            await reviewer.review(request("동일 과제 중복 지원 제한에는 승인 예외가 있습니다."))
            assert model.ainvoke.await_count == 2

    asyncio.run(scenario())


def test_invented_pair_sanction_is_rejected_even_with_valid_evidence_id():
    answer = output()
    answer["insights"][0]["implication"] = "지원이 취소되고 사업비가 환수될 수 있습니다."
    assert validate_pair_output(answer, request()).status == "UNAVAILABLE"


def test_pair_uses_actual_profile_and_invalidates_cache_when_company_context_changes():
    async def scenario():
        reviewer = LegalPairReviewer()
        model = AsyncMock(return_value=None)
        answer = output()
        answer["insights"][0]["verification"] = (
            "두 신청 과제의 목표와 수행 범위를 대조합니다."
        )
        model.ainvoke.return_value = answer
        module = "app.domains.analysis.legal_pair"
        with (
            patch(f"{module}.AnalysisSettings.from_env") as settings,
            patch(f"{module}.ChatOpenAI") as factory,
        ):
            settings.return_value.model_name = "test-model"
            settings.return_value.api_key = "test-key"
            factory.return_value.with_structured_output.return_value = model
            result = await reviewer.review(request())
            assert result.uses_demo_profile is False
            assert result.model_dump(by_alias=True)["usesDemoProfile"] is False
            prompt = model.ainvoke.call_args.args[0]
            assert "BISTelligence" in prompt
            assert "SYNTHETIC_DEMO" not in prompt and "DEMO-NEED-GPU" not in prompt
            await reviewer.review(request())
            assert model.ainvoke.await_count == 1
            updated = replace(
                BISTELLIGENCE_PROFILE,
                evidence_limitations=("추가 증빙 확인이 필요한 회사 정보입니다.",),
            )
            with patch(f"{module}.BISTELLIGENCE_PROFILE", updated):
                result = await reviewer.review(request())
                assert result.uses_demo_profile is False
                assert "추가 증빙 확인이 필요한 회사 정보입니다." in model.ainvoke.call_args.args[0]
            assert model.ainvoke.await_count == 2

    asyncio.run(scenario())


def test_pair_normalizes_known_endings_and_rejects_fragments():
    candidate = output()
    candidate["insights"][0]["comparison"] = "두 공고의 목적에 공통점이 있다."
    candidate["insights"][0]["verification"] = "실제 수행 범위 확인"
    result = validate_pair_output(candidate, request())
    assert result.status == "COMPLETED"
    assert result.insights[0].comparison == "두 공고의 목적에 공통점이 있습니다."
    assert result.insights[0].verification.endswith("확인이 필요합니다.")
    candidate["insights"][0]["verification"] = "실제 수행 범위 및 목표"
    assert validate_pair_output(candidate, request()).status == "UNAVAILABLE"
    assert validate_pair_output({"insights": [12, ["bad"]]}, request()).status == "UNAVAILABLE"
