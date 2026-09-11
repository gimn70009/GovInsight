import json
from dataclasses import replace
from datetime import date
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
    assert profile["targetIndustries"] == ["반도체", "디스플레이", "철강"]
    assert any("AI 에이전트" in service for service in profile["services"])
    assert "제조 데이터 수집·통합" in profile["services"]
    assert "시스템 통합 및 운영" in profile["services"]
    assert "제조 데이터 플랫폼" in profile["relevantProjectTypes"]
    assert any(
        url.startswith("https://www.jobplanet.co.kr/companies/397061")
        for url in profile["sourceUrls"]
    )
    assert previous["available"] is True
    assert previous["eligibility"] == "REVIEW_REQUIRED"


def test_context_without_demo_setting_keeps_real_company_context() -> None:
    context = SimpleNamespace(company_profile=BISTELLIGENCE_PROFILE)

    raw = read_company_profile(context)
    profile = json.loads(raw)

    assert BISTELLIGENCE_PROFILE.demo_profile is not None
    assert "demoProfile" not in profile
    assert "DEMO-RD-01" not in raw
    assert "DEMO-SI-A" not in raw
    assert "staff_capacity" not in profile
    assert "수요기업 A" not in raw
    assert profile["unknownFields"] == list(BISTELLIGENCE_PROFILE.unknown_fields)


def test_explicit_demo_option_preserves_real_facts() -> None:
    context = SimpleNamespace(company_profile=BISTELLIGENCE_PROFILE)
    actual = json.loads(read_company_profile(context))

    combined = json.loads(read_company_profile(context, include_demo=True))
    demo = combined.pop("demoProfile")

    assert combined == actual
    assert demo["dataType"] == "SYNTHETIC_DEMO"
    assert demo["isVerifiedCompanyFact"] is False
    assert "실제 unknownFields를 해소하지 않습니다" in demo["usagePolicy"]
    assert demo["assumed_as_of"] == "2026-09-08"
    documents = {item["name"]: item for item in demo["documents"]}
    assert documents["중소기업확인서"]["status"] == "AVAILABLE"
    assert documents["중소기업확인서"]["valid_until"] == "2027-03-31"
    assert documents["신규 실증 참여확인서"]["status"] == "MISSING"
    assert documents["국세·지방세 납세증명서"]["status"] == "UNKNOWN"
    assert demo["monitoring_purpose"]
    assert demo["supply_capabilities"]
    assert demo["resource_needs"]
    assert demo["notice_evaluation_criteria"]
    assert demo["support_history"]
    assert "target_project" not in demo
    assert "budget_plan" not in demo
    assert "DEMO-NEW-01" not in json.dumps(demo)
    assert "DEMO-RD-01" not in json.dumps(demo)
    assert demo["reusable_assets"]
    assert demo["open_questions"]


def test_profile_without_demo_keeps_legacy_context_even_when_opted_in() -> None:
    context = SimpleNamespace(
        company_profile=replace(BISTELLIGENCE_PROFILE, demo_profile=None),
    )

    assert read_company_profile(context, include_demo=True) == read_company_profile(context)


def test_demo_available_capacity_excludes_existing_work_and_reserves() -> None:
    demo = BISTELLIGENCE_PROFILE.demo_profile
    assert demo is not None
    assert len({staff.role for staff in demo.staff_capacity}) == len(demo.staff_capacity)
    assert all(
        0 <= staff.committed_percent <= 100
        and 0 <= staff.available_percent <= 100
        and 0 <= staff.reserve_percent <= 100
        and staff.committed_percent + staff.available_percent + staff.reserve_percent == 100
        for staff in demo.staff_capacity
    )
    assert sum(staff.available_percent for staff in demo.staff_capacity) == 80


def test_demo_resource_needs_preserve_dates_and_unknown_costs() -> None:
    context = SimpleNamespace(company_profile=BISTELLIGENCE_PROFILE)
    demo = json.loads(read_company_profile(context, include_demo=True))["demoProfile"]
    as_of = date.fromisoformat(demo["assumed_as_of"])
    needs = {item["need_id"]: item for item in demo["resource_needs"]}
    assert len(needs) == len(demo["resource_needs"])
    for need in needs.values():
        assert as_of <= date.fromisoformat(need["desired_start"]) <= date.fromisoformat(
            need["desired_end"],
        )
        assert need["current_state"] and need["intended_use"] and need["requirements"]
        assert need["owner"]
        if need["max_self_payment_won"] is not None:
            assert need["max_self_payment_won"] >= 0
    assert needs["DEMO-NEED-GPU"]["max_self_payment_won"] == 8_000_000
    assert needs["DEMO-NEED-TEST"]["max_self_payment_won"] is None


def test_demo_document_followups_are_dated_and_preserve_unknown_status() -> None:
    demo = BISTELLIGENCE_PROFILE.demo_profile
    assert demo is not None
    as_of = date.fromisoformat(demo.assumed_as_of)
    assert len({item.name for item in demo.documents}) == len(demo.documents)
    assert {item.status for item in demo.documents} == {"AVAILABLE", "MISSING", "UNKNOWN"}
    for document in demo.documents:
        assert document.owner and document.owner != "미정"
        if document.status != "AVAILABLE":
            assert document.target_date is not None
        if document.target_date:
            assert date.fromisoformat(document.target_date) >= as_of
        if document.valid_until:
            assert date.fromisoformat(document.valid_until) >= as_of
            if document.target_date:
                assert date.fromisoformat(document.target_date) <= date.fromisoformat(
                    document.valid_until,
                )
