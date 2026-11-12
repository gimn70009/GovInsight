import asyncio
from dataclasses import replace

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.domains.report import email_review as module
from app.domains.report.config import ReportBriefSettings
from app.domains.report.email_review import (
    Citation,
    EmailReviewRequest,
    ReviewDocument,
    ReviewDraft,
    ReviewSource,
    Sentence,
    summarize_email_reviews,
    validate_copy,
)

FIT = "제조 AI 기술이 공고의 실증 과제와 연결됩니다. 다만 주관기관 수행 실적은 미확인입니다."
GAP = "컨소시엄 구성 여부 | 주관기관과 필수 공동기관의 참여 요건을 확인합니다."
RAW = "제출 공문을 준비합니다. 필수 ① 제출 공문 기관 공문 기관장 직인본 ↵ (HWPX 또는 PDF)"


def document(id=1, fit=FIT):
    return ReviewDocument(
        id=id,
        sources=[
            ReviewSource(id="company_fit", text=fit),
            ReviewSource(id="gap-0", text=GAP),
            ReviewSource(id="gap-1", text=RAW),
        ],
    )


def draft():
    return ReviewDraft(
        review=Sentence(
            text="제조 AI 실증 기회입니다. 주관기관 수행 실적은 추가 확인이 필요합니다.",
            evidence=[Citation(sourceId="company_fit", quote=FIT)],
        ),
        checks=[
            Sentence(
                text="주관기관과 필수 공동기관의 참여 요건을 확인하세요.",
                evidence=[Citation(sourceId="gap-0", quote=GAP)],
            ),
            Sentence(
                text="기관장 직인이 있는 제출 공문을 준비하세요.",
                evidence=[Citation(sourceId="gap-1", quote=RAW)],
            ),
        ],
    )


class Runner:
    def __init__(self, *, fail=False, delay=0):
        self.calls = 0
        self.fail = fail
        self.delay = delay
        self.active = 0
        self.peak = 0

    async def summarize(self, source):
        self.calls += 1
        self.active += 1
        self.peak = max(self.peak, self.active)
        try:
            await asyncio.sleep(self.delay)
            if self.fail:
                raise RuntimeError("mock provider unavailable")
            return draft()
        finally:
            self.active -= 1


@pytest.fixture(autouse=True)
def clear_cache(monkeypatch):
    module._cache.clear()
    module._inflight.clear()
    monkeypatch.setattr(module, "_slots", asyncio.Semaphore(3))


def test_short_copy_retains_caveat_and_discards_raw_table_noise():
    result = validate_copy(draft(), document())
    assert "추가 확인" in result.reviewReason
    assert len(result.reviewReason) <= 160
    assert len(result.priorityChecks) == 2
    assert all(len(text) <= 80 for text in result.priorityChecks)
    assert "①" not in str(result) and "HWPX" not in str(result)


@pytest.mark.parametrize("change", ["quote", "source", "number", "raw", "length"])
def test_unverifiable_or_malformed_copy_is_not_displayed(change):
    output = draft()
    if change == "quote":
        output.checks[0].evidence[0].quote = "입력에 없는 근거입니다."
    elif change == "source":
        output.checks[0].evidence[0].sourceId = "other-notice"
    elif change == "number":
        output.checks[0].text = "필수 공동기관 3곳을 확보하세요."
    elif change == "raw":
        output.checks[0].text = "필수 ① 공문 ↵ (PDF)"
    else:
        output.checks[0].text = "가" * 81
    assert validate_copy(output, document()).priorityChecks == [output.checks[1].text]


def test_cache_deduplicates_concurrent_deliveries_but_changed_analysis_is_new():
    async def run():
        runner = Runner(delay=0.01)
        settings = ReportBriefSettings()
        request = EmailReviewRequest(documents=[document(), document(2)])
        a, b = await asyncio.gather(
            summarize_email_reviews(request, settings=settings, runner=runner),
            summarize_email_reviews(request, settings=settings, runner=runner),
        )
        assert runner.calls == 1
        assert [d.id for d in a.documents] == [1, 2]
        assert a == b
        await summarize_email_reviews(request, settings=settings, runner=runner)
        assert runner.calls == 1
        changed = EmailReviewRequest(
            documents=[document(fit=FIT + " 등록 회사 정보가 변경되었습니다.")]
        )
        await summarize_email_reviews(changed, settings=settings, runner=runner)
        assert runner.calls == 2
        await summarize_email_reviews(
            request, settings=replace(settings, model="other-model"), runner=runner
        )
        assert runner.calls == 3

    asyncio.run(run())


def test_provider_failure_is_short_cached_and_does_not_break_mail():
    async def run():
        runner = Runner(fail=True)
        request = EmailReviewRequest(documents=[document()])
        for _ in range(2):
            response = await summarize_email_reviews(
                request, settings=ReportBriefSettings(), runner=runner
            )
            assert response.documents[0].reviewReason is None
            assert response.documents[0].priorityChecks == []
        assert runner.calls == 1

    asyncio.run(run())


def test_batch_has_a_time_budget_and_global_concurrency_limit():
    async def run():
        runner = Runner(delay=0.2)
        request = EmailReviewRequest(documents=[document(i + 1, FIT + str(i)) for i in range(8)])
        response = await summarize_email_reviews(
            request,
            settings=ReportBriefSettings(
                total_timeout_seconds=0.03, max_total_timeout_seconds=0.03
            ),
            runner=runner,
        )
        assert len(response.documents) == 8
        assert runner.peak <= 3
        assert runner.active == 0
        assert all(d.reviewReason is None for d in response.documents)

    asyncio.run(run())


def test_disabled_or_missing_credentials_never_calls_provider():
    runner = Runner()
    request = EmailReviewRequest(documents=[document()])
    response = asyncio.run(
        summarize_email_reviews(
            request,
            settings=ReportBriefSettings(enabled=False),
            runner=runner,
        )
    )
    assert response.documents == [] and runner.calls == 0
    assert (
        asyncio.run(summarize_email_reviews(request, settings=ReportBriefSettings())).documents
        == []
    )


def test_request_budget_and_duplicate_source_ids_are_validated():
    with pytest.raises(ValidationError):
        ReviewDocument(id=1, sources=[ReviewSource(id="same", text="text")] * 2)
    with pytest.raises(ValidationError):
        ReviewDocument(id=1, sources=[ReviewSource(id=str(i), text="x" * 6000) for i in range(4)])


def test_endpoint_uses_validated_contract(monkeypatch):
    from app.domains.report import api
    from app.main import app

    async def mocked(request):
        return module.EmailReviewResponse(
            documents=[
                module.ReviewResult(
                    id=request.documents[0].id, **validate_copy(draft(), document()).model_dump()
                ),
            ]
        )

    monkeypatch.setattr(api, "summarize_email_reviews", mocked)
    with TestClient(app) as client:
        response = client.post(
            "/internal/monitoring/email-review-briefs",
            json=EmailReviewRequest(documents=[document()]).model_dump(),
        )
        assert response.status_code == 200
        assert (
            response.json()["documents"][0]["priorityChecks"][1]
            == "기관장 직인이 있는 제출 공문을 준비하세요."
        )
        assert (
            client.post(
                "/internal/monitoring/email-review-briefs", json={"documents": [{}]}
            ).status_code
            == 422
        )


def test_positive_only_rewrite_cannot_erase_explicit_company_caveat():
    output = draft()
    output.review.text = "제조 AI 실증 기회입니다."
    assert validate_copy(output, document()).reviewReason is None


def test_email_rewrite_requires_its_own_explicit_enable_setting(monkeypatch):
    monkeypatch.setattr(
        ReportBriefSettings, "from_env", lambda: ReportBriefSettings(api_key="test")
    )
    monkeypatch.delenv("EMAIL_REVIEW_ENABLED", raising=False)
    assert not module.email_review_settings().enabled
    monkeypatch.setenv("EMAIL_REVIEW_ENABLED", "true")
    assert module.email_review_settings().enabled
    monkeypatch.setenv("EMAIL_REVIEW_ENABLED", "invalid")
    with pytest.raises(ValueError):
        module.email_review_settings()


def test_separate_caveat_keeps_the_real_model_from_omitting_limitations():
    output = draft()
    output.review.text = "제조 AI 실증과 사업화 실적 확보 기회입니다."
    output.caveat = Sentence(
        text="주관기관 수행 실적은 추가 확인이 필요합니다.",
        evidence=[Citation(sourceId="company_fit", quote=FIT)],
    )
    result = validate_copy(output, document())
    assert result.reviewReason == output.review.text + " " + output.caveat.text
    assert len(result.reviewReason) <= 160
    output.caveat.evidence[0].quote = "입력에 없는 제한사항입니다."
    assert validate_copy(output, document()).reviewReason is None


@pytest.mark.parametrize(
    "text",
    [
        "연구개발기관 자격 — 회사의 기관 유형을 확인하세요.",
        "연구개발기관 자격: 회사의 기관 유형을 확인하세요.",
        "기관 자격 | 등록 현황을 확인하세요.",
        "연구개발신청서를 준비합니다. - 신청서 원본 1부",
        "연구개발기관 자격 확인 필요",
        "1. 필수 참여기관을 확인하세요.",
    ],
)
def test_fragments_and_label_separators_are_rejected(text):
    output = draft()
    output.checks[0].text = text
    issues = []
    result = validate_copy(output, document(), issues)
    assert result.priorityChecks == [output.checks[1].text]
    assert issues


def test_natural_caveat_and_eligibility_evidence_are_not_lost_to_keyword_filter():
    output = draft()
    output.review.text = "제조 AI 기술의 실증 기회입니다."
    output.caveat = Sentence(
        text="주관기관의 수행 실적은 별도 증빙으로 검증해야 합니다.",
        evidence=[Citation(sourceId="company_fit", quote=FIT)],
    )
    result = validate_copy(output, document())
    assert result.reviewReason == output.review.text + " " + output.caveat.text
    source = ReviewSource(
        id="eligibility-0", text="주관기관의 수행 실적을 별도 증빙으로 검증해야 합니다."
    )
    doc = document()
    doc.sources.append(source)
    output.caveat.evidence = [Citation(sourceId=source.id, quote=source.text)]
    assert validate_copy(output, doc).reviewReason.endswith(output.caveat.text)


def test_verified_caveat_survives_review_failure_or_combined_sentence_limit():
    output = draft()
    output.caveat = Sentence(
        text="주관기관의 수행 실적은 별도 증빙으로 검증해야 합니다.",
        evidence=[Citation(sourceId="company_fit", quote=FIT)],
    )
    # Preserve the condition when the existing review already has two sentences.
    assert validate_copy(output, document()).reviewReason == output.caveat.text
    output.review = None
    assert validate_copy(output, document()).reviewReason == output.caveat.text
    output.review = output.caveat.model_copy(deep=True)
    assert validate_copy(output, document()).reviewReason == output.caveat.text


def test_queued_documents_get_multiple_processing_waves_with_a_hard_batch_cap():
    assert module._batch_budget(ReportBriefSettings(), 3) == 30
    assert module._batch_budget(ReportBriefSettings(), 9) == 75
    assert module._batch_budget(ReportBriefSettings(), 100) == 90

    async def run():
        runner = Runner(delay=0.03)
        response = await summarize_email_reviews(
            EmailReviewRequest(documents=[document(i + 1, FIT + str(i)) for i in range(9)]),
            settings=ReportBriefSettings(
                timeout_seconds=0.1, total_timeout_seconds=0.01, max_total_timeout_seconds=1
            ),
            runner=runner,
        )
        assert runner.calls == 9 and runner.peak == 3
        assert all(d.reviewReason and d.priorityChecks for d in response.documents)

    asyncio.run(run())


def test_partial_results_expire_quickly_and_can_recover_without_analysis_change():
    class PartialRunner(Runner):
        async def summarize(self, source):
            self.calls += 1
            result = draft()
            if self.calls == 1:
                result.review = None
            return result

    async def run():
        runner = PartialRunner()
        request = EmailReviewRequest(documents=[document()])
        settings = ReportBriefSettings()
        first = await summarize_email_reviews(request, settings=settings, runner=runner)
        assert first.documents[0].reviewReason is None
        assert first.documents[0].priorityChecks
        key = module._cache_key(document(), settings)
        expiry, copy = module._cache[key]
        assert 0 < expiry - module.time.monotonic() <= 60
        await summarize_email_reviews(request, settings=settings, runner=runner)
        assert runner.calls == 1
        module._cache[key] = (0, copy)
        recovered = await summarize_email_reviews(request, settings=settings, runner=runner)
        assert recovered.documents[0].reviewReason and runner.calls == 2
        assert module._cache[key][0] - module.time.monotonic() > 86000

    asyncio.run(run())


def test_duplicate_document_ids_are_rejected():
    with pytest.raises(ValidationError):
        EmailReviewRequest(documents=[document(), document()])
