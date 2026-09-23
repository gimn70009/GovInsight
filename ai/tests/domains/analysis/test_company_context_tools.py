import json
from dataclasses import asdict
from types import SimpleNamespace

from app.domains.analysis.company_profile import BISTELLIGENCE_PROFILE
from app.domains.analysis.context_tools import read_company_profile, read_previous_analysis
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.tools import AnalysisToolContext


def test_company_profile_and_previous_analysis_are_available() -> None:
    document = AnalysisDocumentRequest.model_validate(
        {
            "detectionId": 1,
            "documentId": 2,
            "versionId": 3,
            "changeType": "UPDATED_DOCUMENT",
            "organizationName": "산업통상부",
            "boardName": "사업공고",
            "title": "수정 공고",
            "contentText": "지원 조건이 변경되었습니다.",
            "originalUrl": "https://example.go.kr/3",
            "previousAnalysis": {
                "summary": "기존 지원사업 분석 결과입니다.",
                "keyPoints": "[\"기존 조건\"]",
                "eligibility": "REVIEW_REQUIRED",
                "favorableOrNot": "NOT_APPLICABLE",
                "proposalDirection": "기존 제조 AI 제안 방향입니다.",
            },
        }
    )
    context = AnalysisToolContext(document=document, max_text_chars=10_000)

    profile = json.loads(read_company_profile(context))
    previous = json.loads(read_previous_analysis(context))

    assert profile["companyName"] == "BISTelligence(비스텔리젼스)"
    assert "산업 AI" in profile["businessAreas"]
    assert any("중소기업" in fact for fact in profile["verifiedFacts"])
    assert any("반도체" in case for case in profile["caseStudies"])
    assert any("중소기업확인서" in field for field in profile["unknownFields"])
    assert profile["targetIndustries"] == ["반도체", "디스플레이", "철강", "조선", "중공업"]
    assert any("AI 에이전트" in service for service in profile["services"])
    assert "제조 데이터 수집·통합" in profile["services"]
    assert "시스템 통합 및 운영" in profile["services"]
    assert "제조 데이터 플랫폼" in profile["relevantProjectTypes"]
    assert previous["available"] is True
    assert previous["eligibility"] == "REVIEW_REQUIRED"


def test_company_input_contains_real_profile_without_demo_data() -> None:
    context = SimpleNamespace(company_profile=BISTELLIGENCE_PROFILE)
    raw = read_company_profile(context)
    profile = json.loads(raw)
    assert not hasattr(BISTELLIGENCE_PROFILE, "demo_profile")
    assert "demoProfile" not in profile
    assert "SYNTHETIC_DEMO" not in raw
    assert "DEMO-SI-A" not in raw
    assert "DEMO-NEED-GPU" not in raw
    assert profile["unknownFields"] == list(BISTELLIGENCE_PROFILE.unknown_fields)
    assert profile["verifiedFacts"] == list(BISTELLIGENCE_PROFILE.verified_facts)
    assert profile["caseStudies"] == list(BISTELLIGENCE_PROFILE.case_studies)
    assert "sourceUrls" not in profile


def test_legacy_demo_attributes_cannot_reintroduce_synthetic_context() -> None:
    profile = SimpleNamespace(
        **asdict(BISTELLIGENCE_PROFILE),
        demo_profile={"scenario_name": "SHOULD_NOT_BE_INPUT"},
    )
    context = SimpleNamespace(company_profile=profile, include_demo_profile=True)
    raw = read_company_profile(context)
    assert "SHOULD_NOT_BE_INPUT" not in raw
    assert raw == read_company_profile(SimpleNamespace(company_profile=BISTELLIGENCE_PROFILE))
