import json
from datetime import date, timedelta
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.domains.analysis.context.company_profile import BISTELLIGENCE_PROFILE
from app.domains.analysis.context.evaluation_settings import (
    EvaluationSettingsSnapshot,
    profile_for_settings,
)
from app.domains.analysis.schemas.request import AnalysisJobRequest
from app.domains.analysis.schemas.result import (
    OpportunityAssessment,
    OpportunityDimension,
    OpportunityDimensionType,
)
from app.domains.analysis.workflow.agent import SYSTEM_PROMPT
from app.domains.analysis.workflow.configured_scoring import (
    configured_system_prompt,
    criteria_context,
    finalize_configured_score,
)
from tests.domains.analysis.workflow.test_graph import document


@pytest.fixture
def settings():
    root = Path(__file__).resolve().parents[5]
    data = json.loads(
        (root / "backend/src/main/resources/opportunity-settings-defaults.json").read_text(
            encoding="utf-8"
        )
    )
    return EvaluationSettingsSnapshot.model_validate({"revision": 3, "data": data})


@pytest.mark.parametrize("days", [None, 1, 3, 10, 18, 21, 365])
def test_custom_preparation_days_survive_job_roundtrip_and_model_context(settings, days):
    payload = settings.model_dump(mode="json", by_alias=True)
    payload["data"]["criteria"]["preparationDays"] = days
    snapshot = EvaluationSettingsSnapshot.model_validate(payload)
    job = AnalysisJobRequest(run_id=1, documents=[document()], evaluation_settings=snapshot)
    restored = AnalysisJobRequest.model_validate_json(job.model_dump_json(by_alias=True))
    applied = restored.documents[0].evaluation_settings
    assert applied.data.criteria.preparation_days == days
    context = json.loads(criteria_context(applied).split("\n", 1)[1])
    assert context["preparationDays"] == days


@pytest.mark.parametrize("days", [0, -1, 366, 1.5, True, "10", "abc"])
def test_invalid_preparation_days_are_rejected_before_analysis(settings, days):
    payload = settings.model_dump(mode="json", by_alias=True)
    payload["data"]["criteria"]["preparationDays"] = days
    with pytest.raises(ValidationError, match="preparationDays"):
        EvaluationSettingsSnapshot.model_validate(payload)


@pytest.mark.parametrize("window", [1, 3, 7, 10, 14, 18, 30, 365])
def test_custom_urgency_windows_survive_job_and_score_every_boundary(settings, window):
    payload = settings.model_dump(mode="json", by_alias=True)
    payload["data"]["criteria"]["urgencyDays"] = window
    snapshot = EvaluationSettingsSnapshot.model_validate(payload)
    job = AnalysisJobRequest(run_id=1, documents=[document()], evaluation_settings=snapshot)
    restored = AnalysisJobRequest.model_validate_json(job.model_dump_json(by_alias=True))
    applied = restored.documents[0].evaluation_settings
    assert json.loads(criteria_context(applied).split("\n", 1)[1])["urgencyDays"] == window
    today = date(2026, 10, 19)
    for remaining, expected in [
        (-1, 0), (0, 100), (window, 100), (window + 1, 65),
        (window * 2, 65), (window * 2 + 1, 35), (window * 4, 35), (window * 4 + 1, 10),
    ]:
        deadline = (today + timedelta(days=remaining)).isoformat()
        result = finalize_configured_score(assessment(), applied, deadline, today)
        assert result.dimensions[-1].score == expected


@pytest.mark.parametrize("days", [None, 0, -1, 366, 1.5, True, "10", "abc"])
def test_invalid_urgency_days_are_rejected_before_analysis(settings, days):
    payload = settings.model_dump(mode="json", by_alias=True)
    payload["data"]["criteria"]["urgencyDays"] = days
    with pytest.raises(ValidationError, match="urgencyDays"):
        EvaluationSettingsSnapshot.model_validate(payload)


def assessment(reason="신청 마감일의 확인이 필요한 사업입니다.", score=10):
    return OpportunityAssessment(
        dimensions=[
            OpportunityDimension(type=kind, score=score, reason=reason)
            for kind in OpportunityDimensionType
        ]
    )


def test_registered_company_replaces_legacy_context_and_preferences_are_not_facts(settings):
    company = settings.data.company
    company.name = "등록한 회사"
    company.projects = []
    company.certifications = []
    company.technologies = ["센서 설계"]
    settings.data.criteria.tasks = ["진출 희망 기술"]
    profile = profile_for_settings(settings)
    assert profile.company_name == "등록한 회사"
    assert profile.technologies == ("센서 설계",)
    assert "진출 희망 기술" not in str(profile)
    assert not profile.case_studies
    assert any("미등록" in value for value in profile.unknown_fields)
    assert profile_for_settings(None) is BISTELLIGENCE_PROFILE


def test_project_status_does_not_turn_plans_into_completed_evidence(settings):
    payload = settings.model_dump(by_alias=True)
    payload["data"]["company"]["projects"] = [
        {"title": "완료 과제", "description": "공개 수행 사례", "status": "COMPLETED"},
        {"title": "진행 과제", "description": "현재 개발 중", "status": "IN_PROGRESS"},
        {"title": "계획 과제", "description": "아직 시작 전", "status": "PLANNED"},
    ]
    profile = profile_for_settings(EvaluationSettingsSnapshot.model_validate(payload))
    assert len(profile.case_studies) == 1
    assert "완료 과제" in profile.case_studies[0]
    assert all("과제" not in case or "완료 과제" in case for case in profile.case_studies)
    assert sum("완료 실적 아님" in fact for fact in profile.verified_facts) == 2


def test_job_snapshot_is_authoritative_for_every_document(settings):
    job = AnalysisJobRequest(
        run_id=1, documents=[document(), document()], evaluation_settings=settings
    )
    assert all(item.evaluation_settings.revision == 3 for item in job.documents)
    encoded = job.model_dump(mode="json", by_alias=True)
    assert encoded["evaluationSettings"]["revision"] == 3
    assert AnalysisJobRequest.model_validate(encoded).documents[0].evaluation_settings == settings


def test_custom_rubric_replaces_manufacturing_requirement_without_changing_legacy(settings):
    prompt = configured_system_prompt(SYSTEM_PROMPT, settings)
    assert "사용자 설정 기회 점수 산정표" in prompt
    assert "특정 제조 AI 기술을 일률적으로 요구하지 않으며" in prompt
    assert "관심 산업과 직접 일치 40" in prompt
    assert "선택 항목의 개수" in prompt
    assert "사용자 평가 기준 데이터" in criteria_context(settings)
    assert configured_system_prompt(SYSTEM_PROMPT, None) == SYSTEM_PROMPT


def test_business_value_prompt_has_two_components_without_specificity_adjustments(settings):
    prompt = configured_system_prompt(SYSTEM_PROMPT, settings)
    business_value = prompt.split("2. BUSINESS_VALUE", 1)[1].split("3. FEASIBILITY", 1)[0]

    assert "선호 사업 유형과 직접 일치 30/인접 지원 방식\n15/무관 0" in business_value
    assert "직접 수혜 근거 70/간접 기여 근거 30/없음 0" in business_value
    assert "구체적 25" not in business_value
    assert "직접 경제가치" not in business_value
    assert "공고에 명시된 지원·계약·결과물을 근거로 판단" in business_value
    assert "금액·범위의 명시 여부를 별도 점수로 가감하지 않습니다" in business_value
    assert "미확인이어도 직접 수혜 근거가 있으면 직접 수혜 점수를 유지" in business_value
    assert "행사·홍보·네트워킹 가능성만 있으면 최대 40점" in business_value
    assert "지원 규모나 후속 계약을 추측하지" in business_value


@pytest.mark.parametrize(
    "remaining, expected",
    [(-1, 0), (0, 100), (7, 100), (8, 65), (14, 65), (15, 35), (28, 35), (29, 10)],
)
def test_deadline_buckets_are_deterministic_and_do_not_mutate_draft(settings, remaining, expected):
    today = date(2026, 10, 19)
    deadline = (today + timedelta(days=remaining)).isoformat()
    original = assessment()
    result = finalize_configured_score(original, settings, deadline, today)
    assert result.dimensions[-1].score == expected
    assert result.model_dump(by_alias=True)["settingsRevision"] == 3
    assert original.dimensions[-1].score == 10
    assert original.settings_revision == 0


@pytest.mark.parametrize(
    "score, reason",
    [
        (10, "신청 마감일이 원문에서 확인되지 않습니다."),
        (70, "상시 접수이며 예산 소진 시 마감됩니다."),
    ],
)
def test_unknown_and_rolling_deadlines_keep_validated_policy(settings, score, reason):
    result = finalize_configured_score(
        assessment(reason, score), settings, "확인 필요", date.today()
    )
    assert result.dimensions[-1].score == score


def test_no_company_action_never_gets_urgency_from_nearby_date(settings):
    result = finalize_configured_score(
        assessment("회사 행동 없음: 단순 결과 안내 게시글입니다.", 100),
        settings,
        "2026-10-20",
        date(2026, 10, 19),
    )
    assert result.dimensions[-1].score == 0


def test_finalization_and_evidence_use_same_registered_snapshot(settings):
    from app.domains.analysis.evidence.tools import AnalysisToolContext
    from app.domains.analysis.schemas.result import Favorability
    from app.domains.analysis.workflow.graph import DocumentAnalysisWorkflow
    from tests.domains.analysis.workflow.test_graph import SequencedRunner, analysis

    doc = document().model_copy(update={"evaluation_settings": settings})
    settings.data.company.name = "새 등록 회사"
    context = AnalysisToolContext(doc, 1000)
    assert context.company_profile.company_name == "새 등록 회사"
    candidate = analysis(Favorability.NOT_APPLICABLE)
    workflow = DocumentAnalysisWorkflow(runner=SequencedRunner([]), max_attempts=1)
    result = workflow._finalize_node({"document": doc, "candidate": candidate})["result"]
    assert result.opportunity.settings_revision == settings.revision


def test_urgency_explanation_stays_within_callback_length_limit(settings):
    result = finalize_configured_score(
        assessment("설명" * 250), settings, "2026-10-20", date(2026, 10, 19)
    )
    assert len(result.dimensions[-1].reason) <= 500
    assert result.dimensions[-1].score == 100


def test_future_technology_transfer_deadline_does_not_keep_completed_wording(settings):
    import asyncio

    from app.domains.analysis.schemas.result import Eligibility, Favorability
    from app.domains.analysis.workflow.graph import DocumentAnalysisWorkflow
    from tests.domains.analysis.workflow.test_graph import SequencedRunner, analysis

    settings.data.criteria.urgency_days = 21
    candidate = analysis(Favorability.NOT_APPLICABLE)
    candidate.draft.comparison_summary.application_deadline = (
        "2026-10-29 (목) 23:59:00, 시스템 접수 기준으로 마감되었습니다."
    )
    runner = SequencedRunner([candidate])
    result = asyncio.run(DocumentAnalysisWorkflow(
        runner=runner, max_attempts=1, analysis_date=date(2026, 10, 23),
    ).analyze(document().model_copy(update={"evaluation_settings": settings})))

    assert runner.call_count == 1
    assert result.comparison_summary.application_deadline == (
        "2026-10-29 (목) 23:59:00, 시스템 접수 기준 마감입니다."
    )
    urgency = next(d for d in result.opportunity.dimensions if d.type == "URGENCY")
    assert urgency.score == 100
    assert "남은 6일" in urgency.reason
    assert result.eligibility == Eligibility.REVIEW_REQUIRED
