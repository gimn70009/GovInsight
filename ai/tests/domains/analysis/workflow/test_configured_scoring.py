import json
from datetime import date, timedelta
from pathlib import Path

import pytest

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
